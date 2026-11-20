//! Issue #570, a measurement and nothing else: what a narrower model input and a decoder that
//! knows the score format would change in the support-score reader. It changes no behaviour of
//! the shipped reader; it only calls the public pieces (`prepare_crop_with`, the backend, `decode`,
//! `decide`) the way the reader does, and adds a constrained CTC decoder of its own.
//!
//! cargo run --release -p fga-sensing --features tract --example width_decode_eval -- \
//!     <model.onnx> <set A dir> <set B dir> <set P dir> <out dir> \
//!     [--widths 320,256,...] [--timing] [--negatives] [--broad] [--dump <png path>]
//!
//! Set A has an `index.json`, set B a `labels.tsv` (file, text), set P a `list.tsv` and no label.
//! Width 320, RapidOCR's (the reader itself plans `READER_MIN_WIDTH`), is always run first: it is
//! the reference every other width is compared with, and its unshifted read of set A is the
//! fallback truth of the unlabelled fields, as in the evaluation tool's cut sweep.

#[path = "../tests/support/samples.rs"]
mod samples;
#[path = "../tests/support/wrong.rs"]
mod wrong;

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use fga_sensing::backend::{Backend, TractBackend, TractSettings};
use fga_sensing::charset::{BLANK, CharacterList};
use fga_sensing::ctc::{Probabilities, decode};
use fga_sensing::image::GrayImage;
use fga_sensing::preprocess::{DEFAULT_UPSCALE, prepare_crop_with};
use fga_sensing::readers::support_score::{
    ClipReport, Display, Format, Observation, ScoreConfig, SupportScore, decide, inspect_clipping,
    is_featureless, parse,
};
use samples::Sample;
use wrong::{BaseRead, Expect, classify};

const MARGINS: [f64; 7] = [0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 12.0];

struct Args {
    model: PathBuf,
    dir_a: PathBuf,
    dir_b: PathBuf,
    dir_p: PathBuf,
    out: PathBuf,
    widths: Vec<usize>,
    timing: bool,
    negatives: bool,
    broad: bool,
    time_only: bool,
    dump: Option<PathBuf>,
}

fn main() -> ExitCode {
    match parse_args().and_then(|args| run(&args)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("width_decode_eval: {message}");
            ExitCode::FAILURE
        }
    }
}

fn parse_args() -> Result<Args, String> {
    let usage = "usage: width_decode_eval <model.onnx> <set A> <set B> <set P> <out dir> \
                 [--widths 320,160] [--timing] [--negatives] [--broad] [--dump <png>]";
    let mut it = std::env::args().skip(1);
    let mut next = || it.next().ok_or_else(|| usage.to_string());
    let mut args = Args {
        model: PathBuf::from(next()?),
        dir_a: PathBuf::from(next()?),
        dir_b: PathBuf::from(next()?),
        dir_p: PathBuf::from(next()?),
        out: PathBuf::from(next()?),
        widths: vec![320, 256, 192, 160, 128, 112, 96],
        timing: false,
        negatives: false,
        broad: false,
        time_only: false,
        dump: None,
    };
    while let Ok(flag) = next() {
        match flag.as_str() {
            "--timing" => args.timing = true,
            "--negatives" => args.negatives = true,
            "--broad" => args.broad = true,
            "--time-only" => args.time_only = true,
            "--dump" => args.dump = Some(PathBuf::from(next()?)),
            "--widths" => {
                args.widths = next()?
                    .split(',')
                    .map(|w| w.trim().parse::<usize>().map_err(|e| format!("{w}: {e}")))
                    .collect::<Result<_, _>>()?;
            }
            _ => return Err(usage.to_string()),
        }
    }
    // The 320 read is the reference of every other width.
    if !args.time_only {
        args.widths.retain(|&w| w != 320);
        args.widths.insert(0, 320);
    }
    Ok(args)
}

// ---------------------------------------------------------------------------------------------
// The score grammar and the constrained CTC decoder.

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Plus,
    Slash,
    Digit,
}

/// `+D`, `+DD`, `+D/+D`, `+D/+DD`, `+DD/+D`, `+DD/+DD` as a small automaton. Digits are free
/// (`+05` is in the language); the reader's own parse refuses such a text afterwards.
const STATES: usize = 8;
const ACCEPTING: [usize; 4] = [2, 3, 6, 7];

fn delta(state: usize, kind: Kind) -> Option<usize> {
    match (state, kind) {
        (0, Kind::Plus) => Some(1),
        (1, Kind::Digit) => Some(2),
        (2, Kind::Digit) => Some(3),
        (2 | 3, Kind::Slash) => Some(4),
        (4, Kind::Plus) => Some(5),
        (5, Kind::Digit) => Some(6),
        (6, Kind::Digit) => Some(7),
        _ => None,
    }
}

/// The classes that may appear, in one list; a symbol's number is its index plus one (0 is the
/// blank in the path state).
struct Grammar {
    symbols: Vec<(usize, Kind)>,
}

impl Grammar {
    fn new(chars: &CharacterList) -> Result<Self, String> {
        let mut symbols = Vec::new();
        for (c, kind) in std::iter::once(('+', Kind::Plus))
            .chain(std::iter::once(('/', Kind::Slash)))
            .chain(('0'..='9').map(|d| (d, Kind::Digit)))
        {
            let classes = chars.classes_of(c);
            if classes.is_empty() {
                return Err(format!("the model cannot produce {c:?}"));
            }
            symbols.extend(classes.into_iter().map(|class| (class, kind)));
        }
        Ok(Self { symbols })
    }
}

/// What a decoded text is for the rules: the text, RapidOCR's confidence (mean of the five-decimal
/// rounded character probabilities, rounded again) and the weakest character.
#[derive(Clone)]
struct Dec {
    text: String,
    conf: f64,
    min: Option<f32>,
}

fn round5(value: f64) -> f64 {
    (value * 1e5).round_ties_even() / 1e5
}

fn dec_of(text: String, chars: &[f32]) -> Dec {
    let conf = if chars.is_empty() {
        0.0
    } else {
        let sum: f64 = chars.iter().map(|&p| round5(f64::from(p))).sum();
        round5(sum / chars.len() as f64)
    };
    Dec {
        text,
        conf,
        min: chars.iter().copied().reduce(f32::min),
    }
}

struct Cons {
    dec: Dec,
    /// Best unconstrained path score minus the constrained path's, in nats; at least 0.
    delta: f64,
}

fn ln(p: f32) -> f64 {
    f64::from(p).max(1e-30).ln()
}

/// The best CTC path (not the sum over alignments) whose collapsed text is in the grammar. The path
/// state is (automaton state, last frame symbol); a frame that repeats the last symbol adds no
/// label, a blank resets it, any other symbol must be allowed by the automaton. Character
/// confidences are those of the frame where each label's run starts, which is what the greedy
/// decoder reports, so the two confidence floors mean the same thing for both.
fn constrained(
    probs: &Probabilities,
    grammar: &Grammar,
    chars: &CharacterList,
    greedy_logp: f64,
) -> Option<Cons> {
    let lasts = grammar.symbols.len() + 1;
    let at = |q: usize, l: usize| q * lasts + l;
    let mut cur = vec![f64::NEG_INFINITY; STATES * lasts];
    if let Some(start) = cur.get_mut(at(0, 0)) {
        *start = 0.0;
    }
    let mut back: Vec<Vec<(u8, u8)>> = Vec::with_capacity(probs.steps());
    for t in 0..probs.steps() {
        let row = probs.row(t)?;
        let blank = ln(*row.get(BLANK)?);
        let logs: Vec<f64> = grammar
            .symbols
            .iter()
            .map(|&(class, _)| row.get(class).map_or(f64::NEG_INFINITY, |&p| ln(p)))
            .collect();
        let mut next = vec![f64::NEG_INFINITY; STATES * lasts];
        let mut from = vec![(u8::MAX, u8::MAX); STATES * lasts];
        let mut relax = |to: usize, score: f64, q: usize, l: usize| {
            if let (Some(slot), Some(f)) = (next.get_mut(to), from.get_mut(to))
                && score > *slot
            {
                *slot = score;
                *f = (q as u8, l as u8);
            }
        };
        for q in 0..STATES {
            for l in 0..lasts {
                let s = *cur.get(at(q, l))?;
                if s == f64::NEG_INFINITY {
                    continue;
                }
                relax(at(q, 0), s + blank, q, l);
                for (i, &(_, kind)) in grammar.symbols.iter().enumerate() {
                    let sym = i + 1;
                    let lp = *logs.get(i)?;
                    if l == sym {
                        relax(at(q, sym), s + lp, q, l);
                    } else if let Some(q2) = delta(q, kind) {
                        relax(at(q2, sym), s + lp, q, l);
                    }
                }
            }
        }
        back.push(from);
        cur = next;
    }
    let mut best = (f64::NEG_INFINITY, 0usize, 0usize);
    for &q in &ACCEPTING {
        for l in 0..lasts {
            let s = *cur.get(at(q, l))?;
            if s > best.0 {
                best = (s, q, l);
            }
        }
    }
    if best.0 == f64::NEG_INFINITY {
        return None;
    }
    // Walk back; a frame whose symbol differs from the previous frame's starts a label.
    let (mut q, mut l) = (best.1, best.2);
    let mut starts: Vec<(usize, usize)> = Vec::new();
    for t in (0..probs.steps()).rev() {
        let (pq, pl) = *back.get(t)?.get(at(q, l))?;
        if l != 0 && l != usize::from(pl) {
            starts.push((t, l));
        }
        q = usize::from(pq);
        l = usize::from(pl);
    }
    starts.reverse();
    let mut text = String::new();
    let mut confs = Vec::new();
    for (t, sym) in starts {
        let &(class, _) = grammar.symbols.get(sym - 1)?;
        text.push_str(chars.symbol(class)?);
        confs.push(*probs.row(t)?.get(class)?);
    }
    Some(Cons {
        dec: dec_of(text, &confs),
        delta: (greedy_logp - best.0).max(0.0),
    })
}

fn greedy_logp(probs: &Probabilities) -> f64 {
    (0..probs.steps())
        .filter_map(|t| probs.row(t))
        .map(|row| ln(row.iter().copied().fold(0.0f32, f32::max)))
        .sum()
}

// ---------------------------------------------------------------------------------------------
// One crop, everything the policies need.

struct Eval {
    size: (usize, usize),
    clip: ClipReport,
    featureless: bool,
    greedy: Dec,
    cons: Option<Cons>,
}

struct Engine {
    backend: TractBackend,
    grammar: Grammar,
    width: usize,
}

impl Engine {
    fn load(model: &Path, width: usize) -> Result<Self, String> {
        let settings = TractSettings {
            min_width: width,
            threads: 1,
        };
        let backend = TractBackend::load_file_with(model, settings).map_err(|e| e.to_string())?;
        let grammar = Grammar::new(backend.characters())?;
        Ok(Self {
            backend,
            grammar,
            width,
        })
    }

    fn probs(&mut self, crop: &GrayImage) -> Result<Probabilities, String> {
        let input =
            prepare_crop_with(crop, DEFAULT_UPSCALE, self.width).map_err(|e| e.to_string())?;
        self.backend.run(&input).map_err(|e| e.to_string())
    }

    fn greedy(&self, probs: &Probabilities) -> Result<Dec, String> {
        let d = decode(probs, self.backend.characters(), None).map_err(|e| e.to_string())?;
        Ok(dec_of(d.text, &d.char_confidences))
    }

    fn eval(&mut self, crop: &GrayImage) -> Result<Eval, String> {
        let probs = self.probs(crop)?;
        let greedy = self.greedy(&probs)?;
        let cons = constrained(
            &probs,
            &self.grammar,
            self.backend.characters(),
            greedy_logp(&probs),
        );
        Ok(Eval {
            size: (crop.width(), crop.height()),
            clip: inspect_clipping(crop),
            featureless: is_featureless(crop),
            greedy,
            cons,
        })
    }
}

fn par_eval(model: &Path, width: usize, crops: &[GrayImage]) -> Result<Vec<Eval>, String> {
    let workers = std::thread::available_parallelism()
        .map_or(2, |n| n.get())
        .clamp(1, 8);
    let parts: Vec<Result<Vec<(usize, Eval)>, String>> = std::thread::scope(|scope| {
        let handles: Vec<_> = (0..workers)
            .map(|worker| {
                scope.spawn(move || -> Result<Vec<(usize, Eval)>, String> {
                    let mut engine = Engine::load(model, width)?;
                    let mut out = Vec::new();
                    for i in (worker..crops.len()).step_by(workers) {
                        if let Some(crop) = crops.get(i) {
                            out.push((i, engine.eval(crop)?));
                        }
                    }
                    Ok(out)
                })
            })
            .collect();
        handles
            .into_iter()
            .map(|h| h.join().unwrap_or_else(|_| Err("worker panicked".into())))
            .collect()
    });
    let mut all = Vec::new();
    for part in parts {
        all.extend(part?);
    }
    all.sort_by_key(|(i, _)| *i);
    Ok(all.into_iter().map(|(_, e)| e).collect())
}

// ---------------------------------------------------------------------------------------------
// Policies.

#[derive(Clone, Copy, PartialEq)]
enum Policy {
    P0,
    P1,
    P2,
    P3(f64),
}

impl Policy {
    fn name(self) -> String {
        match self {
            Policy::P0 => "P0".into(),
            Policy::P1 => "P1".into(),
            Policy::P2 => "P2".into(),
            Policy::P3(m) => format!("P3@{m}"),
        }
    }
}

fn policies() -> Vec<Policy> {
    let mut list = vec![Policy::P0, Policy::P1, Policy::P2];
    list.extend(MARGINS.iter().map(|&m| Policy::P3(m)));
    list
}

fn parses(text: &str) -> bool {
    parse(text, Format::Strict).is_some()
}

fn chosen(policy: Policy, ev: &Eval) -> &Dec {
    let constrained = ev.cons.as_ref();
    match policy {
        Policy::P0 => &ev.greedy,
        Policy::P1 if parses(&ev.greedy.text) => &ev.greedy,
        Policy::P1 => constrained.map_or(&ev.greedy, |c| &c.dec),
        Policy::P2 => constrained.map_or(&ev.greedy, |c| &c.dec),
        Policy::P3(_) if parses(&ev.greedy.text) => &ev.greedy,
        Policy::P3(margin) => constrained
            .filter(|c| c.delta <= margin)
            .map_or(&ev.greedy, |c| &c.dec),
    }
}

fn score_of(policy: Policy, ev: &Eval, config: &ScoreConfig) -> SupportScore {
    let dec = chosen(policy, ev);
    decide(
        Observation {
            text: dec.text.clone(),
            confidence: dec.conf,
            min_char_confidence: dec.min,
            clip: ev.clip,
            crop_size: ev.size,
            featureless: ev.featureless,
        },
        config,
    )
}

fn show_score(s: &SupportScore) -> String {
    match (s.class.value(), s.grand.value()) {
        (None, None) => format!("unk:{}", s.class.reason().map_or("?", |r| r.name())),
        (c, g) => format!(
            "{}/{}",
            c.map_or("-".into(), |v| v.to_string()),
            g.map_or("-".into(), |v| v.to_string())
        ),
    }
}

fn same_answer(a: &SupportScore, b: &SupportScore) -> bool {
    a.class.value() == b.class.value() && a.grand.value() == b.grand.value()
}

fn show_dec(d: &Dec) -> String {
    format!(
        "{:?} conf {:.4} min {}",
        d.text,
        d.conf,
        d.min.map_or("-".into(), |m| format!("{m:.4}"))
    )
}

// ---------------------------------------------------------------------------------------------
// Labelled items and tallies.

struct Item {
    sample: Sample,
    expect: Expect,
}

fn b_sample(file: &str, class: u8, grand: Option<u8>) -> Sample {
    Sample {
        file: file.to_string(),
        case: String::new(),
        server: "Jp".into(),
        kind: "scan".into(),
        is62: Some(class == 62),
        is16: Some(grand == Some(16)),
        cls: Some(class),
        grand,
        kt_cls: None,
        kt_grand: None,
        known_miss: false,
        py_text: String::new(),
        py_conf: 0.0,
    }
}

fn exact_sample(class: u8, grand: Option<u8>) -> Sample {
    Sample {
        is62: None,
        is16: None,
        ..b_sample("", class, grand)
    }
}

fn exact_expect(class: u8, grand: Option<u8>) -> Expect {
    Expect {
        truth: (Some(class), grand),
        no_grand: grand.is_none(),
    }
}

#[derive(Default, Clone)]
struct Tally {
    n: usize,
    pos62: usize,
    read62: usize,
    pos16: usize,
    read16: usize,
    f62: usize,
    f16: usize,
    other: usize,
    answered: usize,
    no_truth: usize,
}

impl Tally {
    fn add(&mut self, item: &Item, score: &SupportScore) {
        let (class, grand) = (score.class.value(), score.grand.value());
        let s = &item.sample;
        let k = classify(s, item.expect.truth, item.expect.no_grand, class, grand);
        self.n += 1;
        let p62 = s.is62 == Some(true) || s.cls == Some(62);
        let p16 = s.is16 == Some(true) || (s.grand == Some(16) && !item.expect.no_grand);
        self.pos62 += usize::from(p62);
        self.pos16 += usize::from(p16);
        self.read62 += usize::from(p62 && class == Some(62));
        self.read16 += usize::from(p16 && grand == Some(16));
        self.f62 += k.f62;
        self.f16 += k.f16;
        self.other += k.other;
        self.no_truth += k.no_truth;
        self.answered += usize::from(class.is_some() || grand.is_some());
    }

    fn line(&self) -> String {
        format!(
            "n {:4}  +62 {}/{}  +16 {}/{}  F62 {} F16 {} OTHER {}  answered {}  no_truth {}",
            self.n,
            self.read62,
            self.pos62,
            self.read16,
            self.pos16,
            self.f62,
            self.f16,
            self.other,
            self.answered,
            self.no_truth
        )
    }
}

fn tally_set(items: &[Item], evals: &[Eval], policy: Policy, config: &ScoreConfig) -> Tally {
    let mut t = Tally::default();
    for (item, ev) in items.iter().zip(evals) {
        t.add(item, &score_of(policy, ev, config));
    }
    t
}

// ---------------------------------------------------------------------------------------------
// The crops.

fn read_tsv(path: &Path) -> Result<Vec<Vec<String>>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(text
        .lines()
        .skip(1)
        .map(|l| l.split('\t').map(str::to_owned).collect())
        .collect())
}

struct Sets {
    a: Vec<Sample>,
    a_crops: Vec<GrayImage>,
    b_items_raw: Vec<(Sample, Expect)>,
    b_crops: Vec<GrayImage>,
    p_names: Vec<String>,
    p_crops: Vec<GrayImage>,
}

fn load_sets(args: &Args) -> Result<Sets, String> {
    let a = samples::load_index(&args.dir_a)?;
    let a_crops = a
        .iter()
        .map(|s| samples::load_gray(&args.dir_a.join(&s.file)))
        .collect::<Result<Vec<_>, _>>()?;
    let mut b_items_raw = Vec::new();
    let mut b_crops = Vec::new();
    for row in read_tsv(&args.dir_b.join("labels.tsv"))? {
        let (Some(file), Some(text)) = (row.first(), row.get(1)) else {
            return Err("labels.tsv: short row".into());
        };
        let parsed =
            parse(text, Format::Strict).ok_or_else(|| format!("{file}: label {text:?}"))?;
        b_items_raw.push((
            b_sample(file, parsed.class, parsed.grand),
            exact_expect(parsed.class, parsed.grand),
        ));
        b_crops.push(samples::load_gray(&args.dir_b.join(file))?);
    }
    let mut p_names = Vec::new();
    let mut p_crops = Vec::new();
    for row in read_tsv(&args.dir_p.join("list.tsv"))? {
        let Some(file) = row.first() else {
            return Err("list.tsv: short row".into());
        };
        p_names.push(file.clone());
        p_crops.push(samples::load_gray(&args.dir_p.join(file))?);
    }
    Ok(Sets {
        a,
        a_crops,
        b_items_raw,
        b_crops,
        p_names,
        p_crops,
    })
}

/// (file, class, Grand, shifts) of the cut sweep of `tests/model.rs`, with its truths.
type SweepWork = (&'static str, u8, Option<u8>, Vec<(isize, isize)>);

fn model_sweep_work() -> Vec<SweepWork> {
    let known: [(&str, u8, Option<u8>); 10] = [
        ("0183.png", 62, Some(16)),
        ("0006.png", 56, Some(16)),
        ("0033.png", 14, Some(16)),
        ("0299.png", 57, Some(16)),
        ("0044.png", 62, None),
        ("0269.png", 45, None),
        ("0102.png", 0, None),
        ("0179.png", 10, Some(12)),
        ("0195.png", 18, Some(14)),
        ("0188.png", 13, Some(2)),
    ];
    let mut shifts: Vec<(isize, isize)> = vec![];
    for d in 1..=20 {
        shifts.extend([(d, 0), (-d, 0)]);
    }
    for d in 1..=10 {
        shifts.extend([(0, d), (0, -d)]);
    }
    let mut work: Vec<_> = known
        .iter()
        .map(|&(f, c, g)| (f, c, g, shifts.clone()))
        .collect();
    for (file, class) in [
        ("0014.png", 0u8),
        ("0021.png", 6),
        ("0034.png", 1),
        ("0084.png", 2),
    ] {
        work.push((file, class, Some(16), (17..=20).map(|d| (-d, 0)).collect()));
    }
    work
}

fn broad_shifts() -> Vec<(isize, isize)> {
    let mut list = Vec::new();
    for d in 1..=20 {
        list.push((d, 0));
        list.push((-d, 0));
    }
    for d in 1..=10 {
        list.push((0, d));
        list.push((0, -d));
    }
    list
}

// Artificial negatives. None of these is game content.

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
}

fn px(img: &GrayImage, x: isize, y: isize) -> u8 {
    let (w, h) = (img.width() as isize, img.height() as isize);
    let (x, y) = (x.clamp(0, w - 1) as usize, y.clamp(0, h - 1) as usize);
    img.pixels().get(y * img.width() + x).copied().unwrap_or(0)
}

fn rebuild(img: &GrayImage, f: impl Fn(usize, usize) -> u8) -> Result<GrayImage, String> {
    let mut out = Vec::with_capacity(img.width() * img.height());
    for y in 0..img.height() {
        for x in 0..img.width() {
            out.push(f(x, y));
        }
    }
    GrayImage::new(img.width(), img.height(), out).map_err(|e| e.to_string())
}

/// Shift with the edge column repeated.
fn shift_edge(img: &GrayImage, dx: isize) -> Result<GrayImage, String> {
    rebuild(img, |x, y| px(img, x as isize - dx, y as isize))
}

fn swap_halves(img: &GrayImage) -> Result<GrayImage, String> {
    let h = img.height();
    rebuild(img, |x, y| px(img, x as isize, ((y + h / 2) % h) as isize))
}

fn mirror(img: &GrayImage) -> Result<GrayImage, String> {
    let w = img.width() as isize;
    rebuild(img, |x, y| px(img, w - 1 - x as isize, y as isize))
}

fn blur(img: &GrayImage, times: usize) -> Result<GrayImage, String> {
    const K: [u32; 3] = [1, 2, 1];
    let mut cur = img.clone();
    for _ in 0..times {
        let horizontal = rebuild(&cur, |x, y| {
            let sum: u32 = K
                .iter()
                .enumerate()
                .map(|(i, k)| k * u32::from(px(&cur, x as isize + i as isize - 1, y as isize)))
                .sum();
            ((sum + 2) / 4) as u8
        })?;
        cur = rebuild(&horizontal, |x, y| {
            let sum: u32 = K
                .iter()
                .enumerate()
                .map(|(i, k)| {
                    k * u32::from(px(&horizontal, x as isize, y as isize + i as isize - 1))
                })
                .sum();
            ((sum + 2) / 4) as u8
        })?;
    }
    Ok(cur)
}

fn column_shuffle(img: &GrayImage, rng: &mut Rng) -> Result<GrayImage, String> {
    let mut order: Vec<usize> = (0..img.width()).collect();
    for i in (1..order.len()).rev() {
        let j = (rng.next() % (i as u64 + 1)) as usize;
        order.swap(i, j);
    }
    rebuild(img, |x, y| {
        px(img, order.get(x).copied().unwrap_or(0) as isize, y as isize)
    })
}

fn flat(value: u8) -> Result<GrayImage, String> {
    GrayImage::new(60, 30, vec![value; 60 * 30]).map_err(|e| e.to_string())
}

fn uniform_noise(rng: &mut Rng) -> Result<GrayImage, String> {
    GrayImage::new(
        60,
        30,
        (0..60 * 30).map(|_| (rng.next() & 0xFF) as u8).collect(),
    )
    .map_err(|e| e.to_string())
}

/// Random values on a 7x4 grid, bilinearly enlarged: blobs of about the size of a glyph.
fn smooth_noise(rng: &mut Rng) -> Result<GrayImage, String> {
    let grid: Vec<f32> = (0..7 * 4).map(|_| (rng.next() & 0xFF) as f32).collect();
    let g = |x: usize, y: usize| grid.get(y.min(3) * 7 + x.min(6)).copied().unwrap_or(0.0);
    let img = flat(0)?;
    rebuild(&img, |x, y| {
        let (fx, fy) = (x as f32 / 10.0, y as f32 / 10.0);
        let (x0, y0) = (fx as usize, fy as usize);
        let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
        let top = g(x0, y0) * (1.0 - tx) + g(x0 + 1, y0) * tx;
        let bottom = g(x0, y0 + 1) * (1.0 - tx) + g(x0 + 1, y0 + 1) * tx;
        (top * (1.0 - ty) + bottom * ty) as u8
    })
}

struct Negatives {
    crops: Vec<GrayImage>,
    /// Kind per crop.
    kinds: Vec<String>,
    /// For crops derived from a labelled crop that keeps its score: the source's item.
    source: Vec<Option<usize>>,
}

/// `sources` are the labelled crops of A (370) and B (148) with their items, in that order.
fn build_negatives(
    sources: &[(&GrayImage, usize)],
    no_score: &[&GrayImage],
) -> Result<Negatives, String> {
    let mut n = Negatives {
        crops: Vec::new(),
        kinds: Vec::new(),
        source: Vec::new(),
    };
    let push = |n: &mut Negatives, kind: String, img: GrayImage, src: Option<usize>| {
        n.crops.push(img);
        n.kinds.push(kind);
        n.source.push(src);
    };
    let mut rng = Rng(570);
    for &(img, item) in sources {
        for dx in [-12isize, -6, -3, 3, 6, 12] {
            push(
                &mut n,
                format!("shift_edge{dx:+}"),
                shift_edge(img, dx)?,
                Some(item),
            );
        }
        push(&mut n, "blur2".into(), blur(img, 2)?, Some(item));
        push(&mut n, "blur6".into(), blur(img, 6)?, Some(item));
        push(&mut n, "swap_halves".into(), swap_halves(img)?, None);
        push(&mut n, "mirror".into(), mirror(img)?, None);
        push(
            &mut n,
            "column_shuffle".into(),
            column_shuffle(img, &mut rng)?,
            None,
        );
    }
    for v in [0u8, 32, 64, 96, 128, 160, 192, 224, 255] {
        push(&mut n, "flat_grey".into(), flat(v)?, None);
    }
    for _ in 0..100 {
        push(
            &mut n,
            "noise_uniform".into(),
            uniform_noise(&mut rng)?,
            None,
        );
        push(&mut n, "noise_smooth".into(), smooth_noise(&mut rng)?, None);
    }
    for img in no_score {
        push(&mut n, "index_no_score".into(), (*img).clone(), None);
    }
    Ok(n)
}

// ---------------------------------------------------------------------------------------------
// Reporting.

fn stats(values: &mut [f64]) -> String {
    values.sort_by(f64::total_cmp);
    let at = |q: f64| {
        values
            .get(((values.len() as f64 - 1.0) * q).round() as usize)
            .copied()
            .unwrap_or(0.0)
    };
    format!(
        "median {:.2} p90 {:.2} max {:.2} ms (n {})",
        at(0.5),
        at(0.9),
        at(1.0),
        values.len()
    )
}

struct Timing {
    field_ms: Vec<f64>,
    run_ms: Vec<f64>,
    vit_ms: Vec<f64>,
    p1_extra_ms: f64,
    crops: usize,
}

fn time_width(model: &Path, width: usize, crops: &[&GrayImage]) -> Result<Timing, String> {
    let mut engine = Engine::load(model, width)?;
    for crop in crops.iter().take(40) {
        engine.eval(crop)?;
    }
    let mut t = Timing {
        field_ms: Vec::new(),
        run_ms: Vec::new(),
        vit_ms: Vec::new(),
        p1_extra_ms: 0.0,
        crops: 0,
    };
    for _ in 0..3 {
        for crop in crops {
            let t0 = Instant::now();
            let input =
                prepare_crop_with(crop, DEFAULT_UPSCALE, width).map_err(|e| e.to_string())?;
            let t1 = Instant::now();
            let probs = engine.backend.run(&input).map_err(|e| e.to_string())?;
            let t2 = Instant::now();
            let greedy = engine.greedy(&probs)?;
            let t3 = Instant::now();
            let logp = greedy_logp(&probs);
            let _ = constrained(&probs, &engine.grammar, engine.backend.characters(), logp);
            let t4 = Instant::now();
            let ms = |a: Instant, b: Instant| b.duration_since(a).as_secs_f64() * 1e3;
            t.field_ms.push(ms(t0, t3));
            t.run_ms.push(ms(t1, t2));
            let vit = ms(t3, t4);
            t.vit_ms.push(vit);
            if !parses(&greedy.text) {
                t.p1_extra_ms += vit;
            }
            t.crops += 1;
        }
    }
    Ok(t)
}

fn load_times(model: &Path, width: usize) -> Result<Vec<f64>, String> {
    let mut times = Vec::new();
    for _ in 0..3 {
        let t0 = Instant::now();
        Engine::load(model, width)?;
        times.push(t0.elapsed().as_secs_f64() * 1e3);
    }
    times.sort_by(f64::total_cmp);
    Ok(times)
}

fn dump(model: &Path, width: usize, path: &Path) -> Result<(), String> {
    let mut engine = Engine::load(model, width)?;
    let crop = samples::load_gray(path)?;
    let probs = engine.probs(&crop)?;
    let chars = engine.backend.characters().clone();
    println!(
        "--- per-step probabilities of {} at width {width} ({} steps)",
        path.display(),
        probs.steps()
    );
    let name = |c: usize| {
        if c == BLANK {
            "_".to_string()
        } else {
            chars.symbol(c).unwrap_or("?").to_string()
        }
    };
    let plus = chars.classes_of('+');
    for t in 0..probs.steps() {
        let Some(row) = probs.row(t) else { continue };
        let mut top: Vec<(usize, f32)> = row.iter().copied().enumerate().collect();
        top.sort_by(|a, b| b.1.total_cmp(&a.1));
        let shown: Vec<String> = top
            .iter()
            .take(3)
            .filter(|(_, p)| *p >= 0.005)
            .map(|(c, p)| format!("{}:{:.3}", name(*c), p))
            .collect();
        let p_plus: f32 = plus.iter().filter_map(|&c| row.get(c)).sum();
        println!("  step {t:2}  p('+') {p_plus:.4}  top {}", shown.join(" "));
    }
    Ok(())
}

fn run(args: &Args) -> Result<(), String> {
    std::fs::create_dir_all(&args.out).map_err(|e| e.to_string())?;
    let sets = load_sets(args)?;
    let config = ScoreConfig::default();
    let pols = policies();

    // The no-score crops of the index: neither 62 nor 16, and a Python text that is no score.
    // Not looked at by eye.
    let no_score: Vec<&GrayImage> = sets
        .a
        .iter()
        .zip(&sets.a_crops)
        .filter(|(s, _)| s.is62 == Some(false) && s.is16 == Some(false) && !parses(&s.py_text))
        .map(|(_, c)| c)
        .collect();

    let mut base_a: Vec<Option<BaseRead>> = Vec::new();
    let mut ref320: Option<RefRead> = None;

    for &width in &args.widths {
        println!("\n================ width {width} ================");
        if let Some(path) = &args.dump {
            dump(&args.model, width, path)?;
        }
        let ev_a = par_eval(&args.model, width, &sets.a_crops)?;
        let ev_b = par_eval(&args.model, width, &sets.b_crops)?;
        let ev_p = par_eval(&args.model, width, &sets.p_crops)?;
        if width == 320 {
            base_a = ev_a
                .iter()
                .map(|ev| {
                    let s = score_of(Policy::P0, ev, &config);
                    Some(BaseRead {
                        class: s.class.value(),
                        grand: s.grand.value(),
                        ordinary: s.display == Display::Ordinary,
                    })
                })
                .collect();
        }
        let items_a: Vec<Item> = sets
            .a
            .iter()
            .zip(&base_a)
            .map(|(s, b)| Item {
                sample: clone_sample(s),
                expect: wrong::expect(s, *b),
            })
            .collect();
        let items_b: Vec<Item> = sets
            .b_items_raw
            .iter()
            .map(|(s, e)| Item {
                sample: clone_sample(s),
                expect: *e,
            })
            .collect();

        // The cut sweep of tests/model.rs.
        let mut sweep_items: Vec<Item> = Vec::new();
        let mut sweep_crops: Vec<GrayImage> = Vec::new();
        for (file, class, grand, shifts) in model_sweep_work() {
            let crop = samples::load_gray(&args.dir_a.join(file))?;
            for (dx, dy) in shifts {
                sweep_crops.push(samples::shifted(&crop, dx, dy)?);
                sweep_items.push(Item {
                    sample: exact_sample(class, grand),
                    expect: exact_expect(class, grand),
                });
            }
        }
        let ev_sw = par_eval(&args.model, width, &sweep_crops)?;

        // ---- tables
        println!("-- set A ({} crops)", items_a.len());
        for &p in &pols {
            println!(
                "  {:8} {}",
                p.name(),
                tally_set(&items_a, &ev_a, p, &config).line()
            );
        }
        for server in ["Jp", "Kr"] {
            let idx: Vec<usize> = (0..items_a.len())
                .filter(|&i| items_a.get(i).is_some_and(|it| it.sample.server == server))
                .collect();
            let mut t = Tally::default();
            for &i in &idx {
                if let (Some(it), Some(ev)) = (items_a.get(i), ev_a.get(i)) {
                    t.add(it, &score_of(Policy::P0, ev, &config));
                }
            }
            println!("  P0 only {server}: {}", t.line());
        }
        println!("-- set B ({} crops)", items_b.len());
        for &p in &pols {
            println!(
                "  {:8} {}",
                p.name(),
                tally_set(&items_b, &ev_b, p, &config).line()
            );
        }
        println!(
            "-- cut sweep of tests/model.rs ({} crops)",
            sweep_items.len()
        );
        for &p in &pols {
            println!(
                "  {:8} {}",
                p.name(),
                tally_set(&sweep_items, &ev_sw, p, &config).line()
            );
        }

        // ---- set P
        println!("-- set P (no trusted label)");
        for (name, ev) in sets.p_names.iter().zip(&ev_p) {
            let p0 = score_of(Policy::P0, ev, &config);
            let p1 = score_of(Policy::P1, ev, &config);
            let cons = ev.cons.as_ref().map_or("none".to_string(), |c| {
                format!("{} delta {:.2}", show_dec(&c.dec), c.delta)
            });
            println!(
                "  {name}  P0 {} [{}]  P1 {} [{}]",
                show_score(&p0),
                show_dec(&ev.greedy),
                show_score(&p1),
                cons
            );
        }

        // ---- changes against the references
        if width != 320
            && let Some(r) = &ref320
        {
            println!("-- answers that differ from width 320 under P0 (old -> new)");
            report_width_changes("A", &sets.a, &ev_a, &r.a, &config);
            report_width_changes(
                "B",
                &sets
                    .b_items_raw
                    .iter()
                    .map(|(s, _)| clone_sample(s))
                    .collect::<Vec<_>>(),
                &ev_b,
                &r.b,
                &config,
            );
            for (i, (name, ev)) in sets.p_names.iter().zip(&ev_p).enumerate() {
                if let Some(old) = r.p.get(i) {
                    let new = score_of(Policy::P0, ev, &config);
                    let old_s = score_of(Policy::P0, old, &config);
                    if !same_answer(&old_s, &new) {
                        println!(
                            "  P/{name}: {} [{}] -> {} [{}]",
                            show_score(&old_s),
                            show_dec(&old.greedy),
                            show_score(&new),
                            show_dec(&ev.greedy)
                        );
                    }
                }
            }
            let changed = ev_sw
                .iter()
                .zip(&r.sweep)
                .filter(|(n, o)| {
                    !same_answer(
                        &score_of(Policy::P0, n, &config),
                        &score_of(Policy::P0, o, &config),
                    )
                })
                .count();
            println!(
                "  cut sweep crops with another answer than at 320: {changed} of {}",
                ev_sw.len()
            );
        }
        println!(
            "-- answers that change against P0 at this width (P1 = P2 unless noted; delta decides P3)"
        );
        report_policy_changes("A", &sets.a, &ev_a, &config);
        report_policy_changes(
            "B",
            &sets
                .b_items_raw
                .iter()
                .map(|(s, _)| clone_sample(s))
                .collect::<Vec<_>>(),
            &ev_b,
            &config,
        );
        let p2_differs = ev_a
            .iter()
            .chain(&ev_b)
            .chain(&ev_p)
            .chain(&ev_sw)
            .filter(|ev| {
                !same_answer(
                    &score_of(Policy::P1, ev, &config),
                    &score_of(Policy::P2, ev, &config),
                )
            })
            .count();
        println!("  crops where P1 and P2 answer differently: {p2_differs}");

        // ---- the two known misses
        for focus in ["0168.png", "0299.png"] {
            if let Some(i) = sets.a.iter().position(|s| s.file == focus)
                && let Some(ev) = ev_a.get(i)
            {
                println!("-- A/{focus}");
                for &p in &pols {
                    let s = score_of(p, ev, &config);
                    println!(
                        "  {:8} {}  [{}]",
                        p.name(),
                        show_score(&s),
                        show_dec(chosen(p, ev))
                    );
                }
                if let Some(c) = &ev.cons {
                    println!(
                        "  greedy {}  constrained delta {:.3}",
                        show_dec(&ev.greedy),
                        c.delta
                    );
                }
            }
        }

        // ---- broad sweep and negatives
        if args.broad {
            let mut crops = Vec::new();
            let mut items = Vec::new();
            for (item, crop) in items_a
                .iter()
                .zip(&sets.a_crops)
                .chain(items_b.iter().zip(&sets.b_crops))
            {
                let s = &item.sample;
                if !(s.is62 == Some(true)
                    || s.is16 == Some(true)
                    || s.cls.is_some()
                    || s.grand.is_some())
                {
                    continue;
                }
                for (dx, dy) in broad_shifts() {
                    crops.push(samples::shifted(crop, dx, dy)?);
                    items.push(Item {
                        sample: clone_sample(s),
                        expect: item.expect,
                    });
                }
            }
            let evs = par_eval(&args.model, width, &crops)?;
            println!(
                "-- broad cut sweep: every labelled crop of A and B shifted 60 ways ({} crops)",
                items.len()
            );
            for &p in &pols {
                println!(
                    "  {:8} {}",
                    p.name(),
                    tally_set(&items, &evs, p, &config).line()
                );
            }
        }
        if args.negatives {
            negatives_report(
                args, &sets, &items_a, &items_b, &no_score, width, &pols, &config,
            )?;
        }

        // ---- raw output
        write_tsv(args, width, &sets, &ev_a, &ev_b, &ev_p, &config)?;

        if args.timing {
            print_timing(args, &sets, width)?;
        }

        if width == 320 {
            ref320 = Some(RefRead {
                a: ev_a,
                b: ev_b,
                p: ev_p,
                sweep: ev_sw,
            });
        }
    }
    Ok(())
}

fn print_timing(args: &Args, sets: &Sets, width: usize) -> Result<(), String> {
    let main: Vec<&GrayImage> = sets.a_crops.iter().chain(&sets.b_crops).collect();
    let loads = load_times(&args.model, width)?;
    let mut t = time_width(&args.model, width, &main)?;
    println!(
        "-- time at width {width}: load {:.0} / {:.0} / {:.0} ms (min/median/max of 3)",
        loads.first().copied().unwrap_or(0.0),
        loads.get(1).copied().unwrap_or(0.0),
        loads.last().copied().unwrap_or(0.0)
    );
    println!(
        "   per field (prepare + run + greedy decode): {}",
        stats(&mut t.field_ms)
    );
    println!("   model run only: {}", stats(&mut t.run_ms));
    println!("   constrained decode call: {}", stats(&mut t.vit_ms));
    let rounds = t.crops.max(1) as f64;
    println!(
        "   extra per field: P1 {:.4} ms (decode only when greedy fails the format), P2 {:.4} ms (always)",
        t.p1_extra_ms / rounds,
        t.vit_ms.iter().sum::<f64>() / rounds
    );
    Ok(())
}

struct RefRead {
    a: Vec<Eval>,
    b: Vec<Eval>,
    p: Vec<Eval>,
    sweep: Vec<Eval>,
}

fn clone_sample(s: &Sample) -> Sample {
    Sample {
        file: s.file.clone(),
        case: s.case.clone(),
        server: s.server.clone(),
        kind: s.kind.clone(),
        is62: s.is62,
        is16: s.is16,
        cls: s.cls,
        grand: s.grand,
        kt_cls: s.kt_cls,
        kt_grand: s.kt_grand,
        known_miss: s.known_miss,
        py_text: s.py_text.clone(),
        py_conf: s.py_conf,
    }
}

fn report_width_changes(
    set: &str,
    samples: &[Sample],
    now: &[Eval],
    at320: &[Eval],
    config: &ScoreConfig,
) {
    let mut count = 0;
    for ((s, n), o) in samples.iter().zip(now).zip(at320) {
        let new = score_of(Policy::P0, n, config);
        let old = score_of(Policy::P0, o, config);
        if !same_answer(&old, &new) {
            count += 1;
            println!(
                "  {set}/{}: {} [{}] -> {} [{}]",
                s.file,
                show_score(&old),
                show_dec(&o.greedy),
                show_score(&new),
                show_dec(&n.greedy)
            );
        }
    }
    println!("  {set}: {count} answers differ from width 320");
}

fn report_policy_changes(set: &str, samples: &[Sample], evs: &[Eval], config: &ScoreConfig) {
    for (s, ev) in samples.iter().zip(evs) {
        let p0 = score_of(Policy::P0, ev, config);
        let p1 = score_of(Policy::P1, ev, config);
        if !same_answer(&p0, &p1) {
            let delta = ev.cons.as_ref().map_or(f64::NAN, |c| c.delta);
            println!(
                "  {set}/{}: P0 {} [{}] -> P1 {} [{}] delta {:.3}",
                s.file,
                show_score(&p0),
                show_dec(&ev.greedy),
                show_score(&p1),
                ev.cons.as_ref().map_or("none".into(), |c| show_dec(&c.dec)),
                delta
            );
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn negatives_report(
    args: &Args,
    sets: &Sets,
    items_a: &[Item],
    items_b: &[Item],
    no_score: &[&GrayImage],
    width: usize,
    pols: &[Policy],
    config: &ScoreConfig,
) -> Result<(), String> {
    let sources: Vec<(&GrayImage, usize)> = sets
        .a_crops
        .iter()
        .chain(&sets.b_crops)
        .enumerate()
        .map(|(i, c)| (c, i))
        .collect();
    let all_items: Vec<&Item> = items_a.iter().chain(items_b).collect();
    let neg = build_negatives(&sources, no_score)?;
    let evs = par_eval(&args.model, width, &neg.crops)?;
    println!(
        "-- artificial negatives ({} crops), per kind: n, then per policy answered(wrong)",
        neg.crops.len()
    );
    println!(
        "   source-derived kinds (shift_edge, blur) are judged against the source's truth; every other kind shows no score, so any value is wrong"
    );
    let mut kinds: Vec<String> = neg.kinds.clone();
    kinds.sort();
    kinds.dedup();
    let header: Vec<String> = pols.iter().map(|p| p.name()).collect();
    println!("   {:16} {:>5}  {}", "kind", "n", header.join("  "));
    for kind in &kinds {
        let idx: Vec<usize> = (0..neg.kinds.len())
            .filter(|&i| neg.kinds.get(i) == Some(kind))
            .collect();
        let mut cells = Vec::new();
        for &p in pols {
            let (mut answered, mut wrong_n) = (0usize, 0usize);
            for &i in &idx {
                let (Some(ev), Some(src)) = (evs.get(i), neg.source.get(i)) else {
                    continue;
                };
                let s = score_of(p, ev, config);
                let has = s.class.value().is_some() || s.grand.value().is_some();
                answered += usize::from(has);
                match src.and_then(|k| all_items.get(k)) {
                    Some(item) => {
                        let k = classify(
                            &item.sample,
                            item.expect.truth,
                            item.expect.no_grand,
                            s.class.value(),
                            s.grand.value(),
                        );
                        wrong_n += usize::from(k.wrong() > 0);
                    }
                    None => wrong_n += usize::from(has),
                }
            }
            cells.push(format!("{answered}({wrong_n})"));
        }
        println!("   {:16} {:>5}  {}", kind, idx.len(), cells.join("  "));
    }
    println!("   values answered on the kinds that show no score (policy: value x count)");
    for &p in &[Policy::P0, Policy::P1] {
        let mut values: BTreeMap<String, usize> = BTreeMap::new();
        for (i, ev) in evs.iter().enumerate() {
            if neg.source.get(i).copied().flatten().is_some() {
                continue;
            }
            let s = score_of(p, ev, config);
            if s.class.value().is_some() || s.grand.value().is_some() {
                let kind = neg.kinds.get(i).cloned().unwrap_or_default();
                *values
                    .entry(format!("{kind}:{}", show_score(&s)))
                    .or_default() += 1;
            }
        }
        let mut line = String::new();
        for (k, v) in &values {
            let _ = write!(line, " {k} x{v};");
        }
        println!(
            "   {}:{}",
            p.name(),
            if line.is_empty() {
                " none".into()
            } else {
                line
            }
        );
    }
    // The delta of the crops that P1 turns into an answer, for reading the margin table.
    let mut deltas: Vec<(f64, String)> = Vec::new();
    for (i, ev) in evs.iter().enumerate() {
        let p0 = score_of(Policy::P0, ev, config);
        let p1 = score_of(Policy::P1, ev, config);
        if !same_answer(&p0, &p1)
            && p1.class.value().is_some()
            && let Some(c) = &ev.cons
        {
            deltas.push((
                c.delta,
                format!(
                    "{} -> {}",
                    neg.kinds.get(i).cloned().unwrap_or_default(),
                    show_score(&p1)
                ),
            ));
        }
    }
    deltas.sort_by(|a, b| a.0.total_cmp(&b.0));
    println!(
        "   negatives that P1 turns from unknown into a value: {} (smallest deltas first)",
        deltas.len()
    );
    for (d, what) in deltas.iter().take(12) {
        println!("     delta {d:.2}: {what}");
    }
    let mut tsv = String::from(
        "kind\tsource\tgreedy_text\tgreedy_conf\tcons_text\tcons_conf\tdelta\tP0\tP1\n",
    );
    for (i, ev) in evs.iter().enumerate() {
        let (ct, cc, d) = ev
            .cons
            .as_ref()
            .map_or((String::new(), 0.0, f64::NAN), |c| {
                (c.dec.text.clone(), c.dec.conf, c.delta)
            });
        let _ = writeln!(
            tsv,
            "{}\t{}\t{}\t{:.4}\t{}\t{:.4}\t{:.3}\t{}\t{}",
            neg.kinds.get(i).cloned().unwrap_or_default(),
            neg.source
                .get(i)
                .copied()
                .flatten()
                .map_or("-".into(), |s| s.to_string()),
            ev.greedy.text,
            ev.greedy.conf,
            ct,
            cc,
            d,
            show_score(&score_of(Policy::P0, ev, config)),
            show_score(&score_of(Policy::P1, ev, config))
        );
    }
    std::fs::write(args.out.join(format!("w{width}-negatives.tsv")), tsv).map_err(|e| e.to_string())
}

fn write_tsv(
    args: &Args,
    width: usize,
    sets: &Sets,
    ev_a: &[Eval],
    ev_b: &[Eval],
    ev_p: &[Eval],
    config: &ScoreConfig,
) -> Result<(), String> {
    let mut out = String::from(
        "set\tfile\tgreedy_text\tgreedy_conf\tgreedy_min\tcons_text\tcons_conf\tcons_min\tdelta\tP0\tP1\tP2\n",
    );
    let names_a = sets.a.iter().map(|s| s.file.clone());
    let names_b = sets.b_items_raw.iter().map(|(s, _)| s.file.clone());
    let rows = names_a
        .map(|n| ("A", n))
        .zip(ev_a)
        .chain(names_b.map(|n| ("B", n)).zip(ev_b))
        .chain(sets.p_names.iter().map(|n| ("P", n.clone())).zip(ev_p));
    for ((set, file), ev) in rows {
        let (ct, cc, cm, d) = ev
            .cons
            .as_ref()
            .map_or((String::new(), 0.0, None, f64::NAN), |c| {
                (c.dec.text.clone(), c.dec.conf, c.dec.min, c.delta)
            });
        let _ = writeln!(
            out,
            "{set}\t{file}\t{}\t{:.5}\t{}\t{ct}\t{cc:.5}\t{}\t{d:.4}\t{}\t{}\t{}",
            ev.greedy.text,
            ev.greedy.conf,
            ev.greedy.min.map_or("-".into(), |m| format!("{m:.4}")),
            cm.map_or("-".into(), |m| format!("{m:.4}")),
            show_score(&score_of(Policy::P0, ev, config)),
            show_score(&score_of(Policy::P1, ev, config)),
            show_score(&score_of(Policy::P2, ev, config)),
        );
    }
    std::fs::write(args.out.join(format!("w{width}-main.tsv")), out).map_err(|e| e.to_string())
}
