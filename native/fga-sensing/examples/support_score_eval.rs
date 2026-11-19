//! Runs the support-score reader (issue #555) over the local labelled samples and prints the
//! tallies of the acceptance line of design 0025, section 5, against the labels and against the
//! Kotlin reader's stage 0 results.
//!
//! cargo run --release -p fga-sensing --features tract --example support_score_eval -- \
//!     tract <model.onnx> <samples dir> <output.tsv> [--analysis] [--unrestricted] \
//!     [--format strict|compound-class-plus|class-plus|plus-optional] [--range independent|joint] [--floor 0.9] \
//!     [--char-floor 0.5] [--no-clipping]
//!
//! Without `--analysis` it prints the R4 summary for one configuration (the defaults of
//! `ScoreConfig`, changed by the flags) and writes the TSV. With `--analysis` it prints the
//! tables that the thresholds were chosen from: the format, the character restriction, the two
//! confidence floors, the clipping check, with sensitivity and cross-checks.

#[path = "../tests/support/samples.rs"]
mod samples;

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use fga_sensing::Recognizer;
use fga_sensing::backend::Backend;
use fga_sensing::readers::support_score::{
    Format, MAX_CLASS_SCORE, MAX_GRAND_SCORE, Observation, RangePolicy, Reason, Score, ScoreConfig,
    SupportScore, SupportScoreReader, decide, parse,
};
use samples::Sample;

struct Args {
    backend: String,
    model: PathBuf,
    samples: PathBuf,
    output: PathBuf,
    analysis: bool,
    config: ScoreConfig,
}

fn main() -> ExitCode {
    match parse_args().and_then(|args| run(&args)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("support_score_eval: {message}");
            ExitCode::FAILURE
        }
    }
}

fn parse_args() -> Result<Args, String> {
    let usage = "usage: support_score_eval <tract|ort> <model.onnx> <samples dir> <output.tsv> \
                 [--analysis] [--unrestricted] [--format strict|compound-class-plus|class-plus|plus-optional] \
                 [--range independent|joint] [--floor <0..1>] [--char-floor <0..1>] [--no-clipping]";
    let mut args = std::env::args().skip(1);
    let mut next = || args.next().ok_or_else(|| usage.to_string());
    let backend = next()?;
    let model = PathBuf::from(next()?);
    let samples = PathBuf::from(next()?);
    let output = PathBuf::from(next()?);
    let mut parsed = Args {
        backend,
        model,
        samples,
        output,
        analysis: false,
        config: ScoreConfig::default(),
    };
    while let Ok(flag) = next() {
        match flag.as_str() {
            "--analysis" => parsed.analysis = true,
            "--unrestricted" => parsed.config.restrict_characters = false,
            "--no-clipping" => parsed.config.check_clipping = false,
            "--format" => {
                parsed.config.format = match next()?.as_str() {
                    "strict" => Format::Strict,
                    "compound-class-plus" => Format::CompoundClassPlusOptional,
                    "class-plus" => Format::ClassPlusOptional,
                    "plus-optional" => Format::PlusOptional,
                    _ => return Err(usage.to_string()),
                }
            }
            "--range" => {
                parsed.config.range_policy = match next()?.as_str() {
                    "independent" => RangePolicy::Independent,
                    "joint" => RangePolicy::Joint,
                    _ => return Err(usage.to_string()),
                }
            }
            "--floor" => {
                parsed.config.min_confidence = next()?.parse().map_err(|_| usage.to_string())?
            }
            "--char-floor" => {
                parsed.config.min_char_confidence =
                    next()?.parse().map_err(|_| usage.to_string())?
            }
            _ => return Err(usage.to_string()),
        }
    }
    Ok(parsed)
}

fn run(args: &Args) -> Result<(), String> {
    match args.backend.as_str() {
        #[cfg(feature = "tract")]
        "tract" => evaluate(args, fga_sensing::backend::TractBackend::load_file),
        #[cfg(feature = "ort")]
        "ort" => evaluate(args, fga_sensing::backend::OrtBackend::load_file),
        other => Err(format!(
            "backend {other:?} is not compiled in; build with --features {other}"
        )),
    }
}

/// Everything the model and the pixels said about each sample in one recognition mode.
struct Observed {
    rows: Vec<Observation>,
    micros: Vec<u128>,
}

fn observe_all<B: Backend>(
    args: &Args,
    index: &[Sample],
    load: &impl Fn(&Path) -> Result<B, fga_sensing::Error>,
    restrict: bool,
) -> Result<Observed, String> {
    let backend = load(&args.model).map_err(|e| e.to_string())?;
    let config = ScoreConfig {
        restrict_characters: restrict,
        ..args.config.clone()
    };
    let mut reader =
        SupportScoreReader::new(Recognizer::new(backend), config).map_err(|e| e.to_string())?;
    let mut observed = Observed {
        rows: Vec::new(),
        micros: Vec::new(),
    };
    for sample in index {
        let crop = samples::load_gray(&args.samples.join(&sample.file))?;
        let started = Instant::now();
        let row = reader
            .observe(&crop)
            .map_err(|e| format!("{}: {e}", sample.file))?;
        observed.micros.push(started.elapsed().as_micros());
        observed.rows.push(row);
    }
    Ok(observed)
}

fn evaluate<B: Backend>(
    args: &Args,
    load: impl Fn(&Path) -> Result<B, fga_sensing::Error>,
) -> Result<(), String> {
    let index = samples::load_index(&args.samples)?;
    let main_mode = observe_all(args, &index, &load, args.config.restrict_characters)?;
    if args.analysis {
        let other_mode = observe_all(args, &index, &load, !args.config.restrict_characters)?;
        let (restricted, unrestricted) = if args.config.restrict_characters {
            (&main_mode, &other_mode)
        } else {
            (&other_mode, &main_mode)
        };
        analysis(&index, restricted, unrestricted);
        return Ok(());
    }
    let results = decide_all(&main_mode, &args.config);
    write_tsv(&args.output, &index, &main_mode, &results)?;
    summary(args, &index, &main_mode, &results);
    Ok(())
}

fn decide_all(observed: &Observed, config: &ScoreConfig) -> Vec<SupportScore> {
    observed
        .rows
        .iter()
        .map(|row| decide(row.clone(), config))
        .collect()
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Verdict {
    Ok,
    Wrong,
    Unknown,
    /// A value that the labels do not decide.
    Unlabelled,
}

impl Verdict {
    fn tag(self) -> &'static str {
        match self {
            Verdict::Ok => "ok",
            Verdict::Wrong => "WRONG",
            Verdict::Unknown => "unknown",
            Verdict::Unlabelled => "?",
        }
    }
}

/// `exact` is the labelled number, `flag` the labelled "is it the wanted special value" of the
/// same field, `special` that value (62 or 16).
fn verdict(value: Option<u8>, exact: Option<u8>, flag: Option<bool>, special: u8) -> Verdict {
    match (value, exact, flag) {
        (None, ..) => Verdict::Unknown,
        (Some(v), Some(label), _) => {
            if v == label {
                Verdict::Ok
            } else {
                Verdict::Wrong
            }
        }
        (Some(v), None, Some(wanted)) if v == special => {
            if wanted {
                Verdict::Ok
            } else {
                Verdict::Wrong
            }
        }
        (Some(_), None, Some(true)) => Verdict::Wrong,
        (Some(_), None, _) => Verdict::Unlabelled,
    }
}

fn class_verdict(sample: &Sample, value: Option<u8>) -> Verdict {
    verdict(value, sample.cls, sample.is62, 62)
}

fn grand_verdict(sample: &Sample, value: Option<u8>) -> Verdict {
    verdict(value, sample.grand, sample.is16, 16)
}

#[derive(Default, Clone, Debug)]
struct Tally {
    positives62: usize,
    read62: usize,
    false_accepts62: usize,
    positives16: usize,
    read16: usize,
    false_accepts16: usize,
    class_labelled: usize,
    class_equal: usize,
    class_unknown: usize,
    class_wrong: usize,
    grand_labelled: usize,
    grand_equal: usize,
    grand_unknown: usize,
    grand_wrong: usize,
}

impl Tally {
    fn misses62(&self) -> usize {
        self.positives62 - self.read62
    }

    fn misses16(&self) -> usize {
        self.positives16 - self.read16
    }

    fn false_accepts(&self) -> usize {
        self.false_accepts62 + self.false_accepts16
    }

    fn wrong(&self) -> usize {
        self.class_wrong + self.grand_wrong
    }
}

fn tally<'a>(rows: impl Iterator<Item = (&'a Sample, &'a SupportScore)>) -> Tally {
    let mut t = Tally::default();
    for (sample, score) in rows {
        let class = score.class.value();
        let grand = score.grand.value();
        match sample.is62 {
            Some(true) => {
                t.positives62 += 1;
                t.read62 += usize::from(class == Some(62));
            }
            Some(false) => t.false_accepts62 += usize::from(class == Some(62)),
            None => {}
        }
        match sample.is16 {
            Some(true) => {
                t.positives16 += 1;
                t.read16 += usize::from(grand == Some(16));
            }
            Some(false) => t.false_accepts16 += usize::from(grand == Some(16)),
            None => {}
        }
        if let Some(label) = sample.cls {
            t.class_labelled += 1;
            match class {
                Some(v) if v == label => t.class_equal += 1,
                Some(_) => t.class_wrong += 1,
                None => t.class_unknown += 1,
            }
        }
        if let Some(label) = sample.grand {
            t.grand_labelled += 1;
            match grand {
                Some(v) if v == label => t.grand_equal += 1,
                Some(_) => t.grand_wrong += 1,
                None => t.grand_unknown += 1,
            }
        }
    }
    t
}

fn show(value: Option<u8>) -> String {
    value.map_or("-".to_string(), |v| v.to_string())
}

fn show_label(sample: &Sample) -> String {
    let flag = |f: Option<bool>| f.map_or("?", |b| if b { "yes" } else { "no" });
    format!(
        "label is62={} is16={} cls={} grand={}",
        flag(sample.is62),
        flag(sample.is16),
        show(sample.cls),
        show(sample.grand)
    )
}

fn write_tsv(
    path: &Path,
    index: &[Sample],
    observed: &Observed,
    results: &[SupportScore],
) -> Result<(), String> {
    let mut out = String::from(
        "file\tcase\tserver\tkind\tis62\tis16\tcls\tgrand\ttext\tconfidence\tmin_char\tclip_right\t\
         clip_vertical\tclass\tclass_reason\tgrand_out\tgrand_reason\tdisplay\tkt_cls\tkt_grand\tus\n",
    );
    let flag = |f: Option<bool>| f.map_or("", |b| if b { "1" } else { "0" });
    let reason = |s: Score| s.reason().map_or("", |r| r.name());
    for ((sample, score), micros) in index.iter().zip(results).zip(&observed.micros) {
        let o = &score.observation;
        let _ = writeln!(
            out,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.5}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            sample.file,
            sample.case,
            sample.server,
            sample.kind,
            flag(sample.is62),
            flag(sample.is16),
            sample.cls.map_or(String::new(), |v| v.to_string()),
            sample.grand.map_or(String::new(), |v| v.to_string()),
            o.text,
            o.confidence,
            o.min_char_confidence
                .map_or(String::new(), |c| format!("{c:.5}")),
            u8::from(o.clip.right),
            u8::from(o.clip.vertical),
            score.class.value().map_or(String::new(), |v| v.to_string()),
            reason(score.class),
            score.grand.value().map_or(String::new(), |v| v.to_string()),
            reason(score.grand),
            score.display.name(),
            sample.kt_cls.map_or(String::new(), |v| v.to_string()),
            sample.kt_grand.map_or(String::new(), |v| v.to_string()),
            micros
        );
    }
    std::fs::write(path, out).map_err(|e| format!("{}: {e}", path.display()))
}

fn describe(config: &ScoreConfig) -> String {
    format!(
        "format={:?} range={:?} floor={} char_floor={} restricted={} clipping={}",
        config.format,
        config.range_policy,
        config.min_confidence,
        config.min_char_confidence,
        config.restrict_characters,
        config.check_clipping
    )
}

fn summary(args: &Args, index: &[Sample], observed: &Observed, results: &[SupportScore]) {
    let t = tally(index.iter().zip(results));
    println!("config: {}", describe(&args.config));
    println!("samples={}", index.len());
    println!(
        "+62: read {} of {} labelled positives, missed {}, false accepts {} | Kotlin baseline misses 12, false accepts 0",
        t.read62,
        t.positives62,
        t.misses62(),
        t.false_accepts62
    );
    println!(
        "+16: read {} of {} labelled positives, missed {}, false accepts {} | Kotlin baseline misses 25, false accepts 0",
        t.read16,
        t.positives16,
        t.misses16(),
        t.false_accepts16
    );
    println!(
        "class value: labelled {}, equal {}, unknown {}, WRONG {}",
        t.class_labelled, t.class_equal, t.class_unknown, t.class_wrong
    );
    println!(
        "grand value: labelled {}, equal {}, unknown {}, WRONG {}",
        t.grand_labelled, t.grand_equal, t.grand_unknown, t.grand_wrong
    );

    let mut class_reasons: BTreeMap<Reason, usize> = BTreeMap::new();
    let mut grand_reasons: BTreeMap<Reason, usize> = BTreeMap::new();
    for score in results {
        if let Some(r) = score.class.reason() {
            *class_reasons.entry(r).or_default() += 1;
        }
        if let Some(r) = score.grand.reason() {
            *grand_reasons.entry(r).or_default() += 1;
        }
    }
    let list = |m: &BTreeMap<Reason, usize>| {
        Reason::ALL
            .iter()
            .map(|r| format!("{}={}", r.name(), m.get(r).copied().unwrap_or(0)))
            .collect::<Vec<_>>()
            .join(" ")
    };
    println!("unknown reasons, class field: {}", list(&class_reasons));
    println!("unknown reasons, grand field: {}", list(&grand_reasons));

    // The Kotlin comparison.
    let mut reader_only = Vec::new();
    let mut kotlin_only = Vec::new();
    let mut both_differ = Vec::new();
    let mut same = 0usize;
    for (sample, score) in index.iter().zip(results) {
        let ours = (score.class.value(), score.grand.value());
        let theirs = (sample.kt_cls, sample.kt_grand);
        if ours == theirs {
            same += 1;
            continue;
        }
        let line = format!(
            "  {} ({}) reader class={} grand={} [{} / {}] | Kotlin class={} grand={} [{} / {}] | {}",
            sample.file,
            sample.case,
            show(ours.0),
            show(ours.1),
            class_verdict(sample, ours.0).tag(),
            grand_verdict(sample, ours.1).tag(),
            show(theirs.0),
            show(theirs.1),
            class_verdict(sample, theirs.0).tag(),
            grand_verdict(sample, theirs.1).tag(),
            show_label(sample)
        );
        // A field-wise view: one field may gain while the other is lost.
        let gained =
            (ours.0.is_some() && theirs.0.is_none()) || (ours.1.is_some() && theirs.1.is_none());
        let lost =
            (ours.0.is_none() && theirs.0.is_some()) || (ours.1.is_none() && theirs.1.is_some());
        let changed = (ours.0.is_some() && theirs.0.is_some() && ours.0 != theirs.0)
            || (ours.1.is_some() && theirs.1.is_some() && ours.1 != theirs.1);
        if changed {
            both_differ.push(line);
        } else if gained && !lost {
            reader_only.push(line);
        } else if lost && !gained {
            kotlin_only.push(line);
        } else {
            // Gains in one field and losses in the other.
            both_differ.push(format!("{line} (mixed)"));
        }
    }
    println!(
        "against the Kotlin reader (stage 0 replay): same result {same}, reader has a value and Kotlin none {}, Kotlin has a value and reader none {}, both have a value and differ (or mixed) {}",
        reader_only.len(),
        kotlin_only.len(),
        both_differ.len()
    );
    for (title, lines) in [
        ("reader value, Kotlin unknown", &reader_only),
        ("Kotlin value, reader unknown", &kotlin_only),
        ("both have a value and they differ", &both_differ),
    ] {
        println!("{title}: {}", lines.len());
        for line in lines {
            println!("{line}");
        }
    }

    // The Kotlin reader only returns the scores its policy asks for (62 and 16), so the list above
    // is long by design. The question that matters to the automation is the decision on those two.
    let mut decision_lines: [Vec<String>; 4] = Default::default();
    for (sample, score) in index.iter().zip(results) {
        for (name, ours, theirs, flag, wanted) in [
            ("+62", score.class.value(), sample.kt_cls, sample.is62, 62),
            ("+16", score.grand.value(), sample.kt_grand, sample.is16, 16),
        ] {
            let (we, they) = (ours == Some(wanted), theirs == Some(wanted));
            if we == they {
                continue;
            }
            let line = format!(
                "  {} {name}: reader {} Kotlin {} | label {}",
                sample.file,
                if we { "yes" } else { "no" },
                if they { "yes" } else { "no" },
                flag.map_or("?", |b| if b { "yes" } else { "NO" })
            );
            let slot = usize::from(!we) * 2 + usize::from(flag == Some(false));
            if let Some(list) = decision_lines.get_mut(slot) {
                list.push(line);
            }
        }
    }
    let titles = [
        "decision on 62/16: reader accepts, Kotlin does not, label says yes",
        "decision on 62/16: reader accepts, Kotlin does not, label says NO (a reader false accept)",
        "decision on 62/16: Kotlin accepts, reader does not, label says yes (a reader miss)",
        "decision on 62/16: Kotlin accepts, reader does not, label says NO (a Kotlin false accept)",
    ];
    for (title, lines) in titles.iter().zip(&decision_lines) {
        println!("{title}: {}", lines.len());
        for line in lines {
            println!("{line}");
        }
    }
    println!("acceptance line of design 0025 section 5:");
    println!(
        "  +62 misses {} <= 12: {}",
        t.misses62(),
        if t.misses62() <= 12 { "holds" } else { "FAILS" }
    );
    println!(
        "  +16 misses {} <= 25: {}",
        t.misses16(),
        if t.misses16() <= 25 { "holds" } else { "FAILS" }
    );
    println!(
        "  false accepts {} <= 0: {}",
        t.false_accepts(),
        if t.false_accepts() == 0 {
            "holds"
        } else {
            "FAILS"
        }
    );
    println!(
        "  wrong labelled values {} == 0: {}",
        t.wrong(),
        if t.wrong() == 0 { "holds" } else { "FAILS" }
    );
    let mut times = observed.micros.clone();
    times.sort_unstable();
    let at = |q: usize| times.get(times.len() * q / 100).copied().unwrap_or(0);
    println!(
        "time per field (recognise + pixel check, PC release): median {} us, p90 {} us, max {} us",
        at(50),
        at(90),
        times.last().copied().unwrap_or(0)
    );
}

// ---------------------------------------------------------------------------------------------
// Analysis: the tables the thresholds are chosen from.

fn percentile(sorted: &[f64], q: f64) -> Option<f64> {
    if sorted.is_empty() {
        return None;
    }
    let rank = ((sorted.len() - 1) as f64 * q).round() as usize;
    sorted.get(rank).copied()
}

fn distribution(values: &[f64]) -> String {
    let mut sorted = values.to_vec();
    sorted.sort_unstable_by(f64::total_cmp);
    let p = |q: f64| percentile(&sorted, q).map_or("-".to_string(), |v| format!("{v:.5}"));
    format!(
        "n={} min={} p1={} p5={} p25={} median={} max={}",
        sorted.len(),
        p(0.0),
        p(0.01),
        p(0.05),
        p(0.25),
        p(0.5),
        p(1.0)
    )
}

/// A read at floor 0 whose every returned field the labels agree with (and at least one is
/// confirmed) is a correct read; one with a field the labels contradict is a wrong read.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Correct,
    Wrong,
    Other,
}

fn kind_of(sample: &Sample, score: &SupportScore) -> Kind {
    let verdicts = [
        class_verdict(sample, score.class.value()),
        grand_verdict(sample, score.grand.value()),
    ];
    if verdicts.contains(&Verdict::Wrong) {
        Kind::Wrong
    } else if verdicts.contains(&Verdict::Ok) {
        Kind::Correct
    } else {
        Kind::Other
    }
}

struct Split {
    correct_text: Vec<f64>,
    wrong_text: Vec<f64>,
    correct_char: Vec<f64>,
    wrong_char: Vec<f64>,
    other: usize,
}

/// The two distributions a floor separates, over the rows `keep` selects, with the other
/// thresholds at zero and the format, range and clipping rules as in `config`.
fn split(
    index: &[Sample],
    observed: &Observed,
    config: &ScoreConfig,
    keep: impl Fn(&Sample) -> bool,
) -> Split {
    let open = ScoreConfig {
        min_confidence: 0.0,
        min_char_confidence: 0.0,
        ..config.clone()
    };
    let mut s = Split {
        correct_text: Vec::new(),
        wrong_text: Vec::new(),
        correct_char: Vec::new(),
        wrong_char: Vec::new(),
        other: 0,
    };
    for (sample, row) in index.iter().zip(&observed.rows) {
        if !keep(sample) {
            continue;
        }
        let weakest = row.min_char_confidence.map(f64::from);
        // A value read out of a crop with no texture is a wrong read by construction, which the
        // labels cannot say (they carry no value for such a crop).
        if row.featureless {
            let reads_as_score = parse(&row.text, config.format).is_some_and(|p| {
                p.class <= MAX_CLASS_SCORE && p.grand.is_none_or(|g| g <= MAX_GRAND_SCORE)
            });
            if reads_as_score {
                s.wrong_text.push(row.confidence);
                s.wrong_char.extend(weakest);
            }
            continue;
        }
        let score = decide(row.clone(), &open);
        match kind_of(sample, &score) {
            Kind::Correct => {
                s.correct_text.push(row.confidence);
                s.correct_char.extend(weakest);
            }
            Kind::Wrong => {
                s.wrong_text.push(row.confidence);
                s.wrong_char.extend(weakest);
            }
            Kind::Other => s.other += 1,
        }
    }
    s
}

fn min_of(values: &[f64]) -> Option<f64> {
    values.iter().copied().reduce(f64::min)
}

fn max_of(values: &[f64]) -> Option<f64> {
    values.iter().copied().reduce(f64::max)
}

/// The rule, fixed before any pass count was looked at: with wrong reads present, the midpoint
/// of the gap between the highest wrong and the lowest correct confidence, and no floor if the
/// two overlap; with none, the lowest correct confidence minus 0.05, rounded down to 0.01. A
/// floor is never negative.
#[derive(Debug, Clone, Copy)]
struct Floor {
    value: Option<f64>,
    lowest_correct: Option<f64>,
    highest_wrong: Option<f64>,
}

fn derive_floor(correct: &[f64], wrong: &[f64]) -> Floor {
    let lowest_correct = min_of(correct);
    let highest_wrong = max_of(wrong);
    let value = match (lowest_correct, highest_wrong) {
        (None, _) => None,
        (Some(low), None) => Some(((low - 0.05).max(0.0) * 100.0).floor() / 100.0),
        (Some(low), Some(high)) if high < low => Some((low + high) / 2.0),
        _ => None,
    };
    Floor {
        value,
        lowest_correct,
        highest_wrong,
    }
}

fn fmt_opt(v: Option<f64>) -> String {
    v.map_or("-".to_string(), |v| format!("{v:.5}"))
}

fn line_for(t: &Tally) -> String {
    format!(
        "+62 read {}/{} (miss {}, FA {}) | +16 read {}/{} (miss {}, FA {}) | class equal {}/{} unknown {} WRONG {} | grand equal {}/{} unknown {} WRONG {}",
        t.read62,
        t.positives62,
        t.misses62(),
        t.false_accepts62,
        t.read16,
        t.positives16,
        t.misses16(),
        t.false_accepts16,
        t.class_equal,
        t.class_labelled,
        t.class_unknown,
        t.class_wrong,
        t.grand_equal,
        t.grand_labelled,
        t.grand_unknown,
        t.grand_wrong
    )
}

fn tally_with(
    index: &[Sample],
    observed: &Observed,
    config: &ScoreConfig,
    keep: impl Fn(&Sample) -> bool,
) -> Tally {
    let results = decide_all(observed, config);
    tally(
        index
            .iter()
            .zip(&results)
            .filter(|(sample, _)| keep(sample)),
    )
}

const ALL_FORMATS: [Format; 4] = [
    Format::Strict,
    Format::CompoundClassPlusOptional,
    Format::ClassPlusOptional,
    Format::PlusOptional,
];

fn analysis(index: &[Sample], restricted: &Observed, unrestricted: &Observed) {
    let base = ScoreConfig::default();
    let open = ScoreConfig {
        min_confidence: 0.0,
        min_char_confidence: 0.0,
        ..base.clone()
    };
    println!("== defaults under test: {}", describe(&base));

    println!("\n== R2a format (floors 0, clipping on), per recognition mode");
    for (mode, observed) in [("restricted", restricted), ("unrestricted", unrestricted)] {
        for format in ALL_FORMATS {
            let config = ScoreConfig {
                format,
                ..open.clone()
            };
            let t = tally_with(index, observed, &config, |_| true);
            println!("{mode:12} {format:?}: {}", line_for(&t));
        }
        // Rows the laxest format reads and a stricter one does not, and the reverse.
        let lax_config = ScoreConfig {
            format: Format::PlusOptional,
            ..open.clone()
        };
        let lax = decide_all(observed, &lax_config);
        for format in [
            Format::Strict,
            Format::CompoundClassPlusOptional,
            Format::ClassPlusOptional,
        ] {
            let stricter = decide_all(
                observed,
                &ScoreConfig {
                    format,
                    ..open.clone()
                },
            );
            for ((sample, a), b) in index.iter().zip(&lax).zip(&stricter) {
                let (va, vb) = (
                    (a.class.value(), a.grand.value()),
                    (b.class.value(), b.grand.value()),
                );
                if va != vb {
                    println!(
                        "  {mode} PlusOptional vs {format:?}: {} text={:?} lax class={} grand={} [{}/{}] strict class={} grand={} | {}",
                        sample.file,
                        a.observation.text,
                        show(va.0),
                        show(va.1),
                        class_verdict(sample, va.0).tag(),
                        grand_verdict(sample, va.1).tag(),
                        show(vb.0),
                        show(vb.1),
                        show_label(sample)
                    );
                }
            }
        }
    }

    println!("\n== blank crops read as one digit (restricted vs unrestricted text and confidence)");
    for file in ["0002.png", "0005.png", "0052.png", "0068.png"] {
        for (i, sample) in index.iter().enumerate() {
            if sample.file != file {
                continue;
            }
            let r = restricted.rows.get(i);
            let u = unrestricted.rows.get(i);
            println!(
                "  {file}: restricted text={:?} conf={} | unrestricted text={:?} conf={}",
                r.map(|o| o.text.as_str()).unwrap_or(""),
                fmt_opt(r.map(|o| o.confidence)),
                u.map(|o| o.text.as_str()).unwrap_or(""),
                fmt_opt(u.map(|o| o.confidence))
            );
        }
    }

    println!("\n== R3 confidence floor: distributions (format as defaults, clipping on, floors 0)");
    for (mode, observed) in [("restricted", restricted), ("unrestricted", unrestricted)] {
        let s = split(index, observed, &base, |_| true);
        println!(
            "{mode}: correct reads text confidence {}",
            distribution(&s.correct_text)
        );
        println!(
            "{mode}: wrong reads   text confidence {}",
            distribution(&s.wrong_text)
        );
        println!(
            "{mode}: correct reads weakest character {}",
            distribution(&s.correct_char)
        );
        println!(
            "{mode}: wrong reads   weakest character {}",
            distribution(&s.wrong_char)
        );
        println!("{mode}: reads the labels do not decide: {}", s.other);
        let text = derive_floor(&s.correct_text, &s.wrong_text);
        let character = derive_floor(&s.correct_char, &s.wrong_char);
        println!(
            "{mode}: rule gives text floor {} (lowest correct {}, highest wrong {}), character floor {} (lowest correct {}, highest wrong {})",
            fmt_opt(text.value),
            fmt_opt(text.lowest_correct),
            fmt_opt(text.highest_wrong),
            fmt_opt(character.value),
            fmt_opt(character.lowest_correct),
            fmt_opt(character.highest_wrong)
        );
    }

    println!(
        "
== blank-crop hazard: reads from featureless crops under the laxest format"
    );
    for (mode, observed) in [("restricted", restricted), ("unrestricted", unrestricted)] {
        let lax = ScoreConfig {
            format: Format::PlusOptional,
            ..base.clone()
        };
        let s = split(index, observed, &lax, |_| true);
        println!(
            "{mode}: wrong reads under PlusOptional, text confidence {}; correct reads {}",
            distribution(&s.wrong_text),
            distribution(&s.correct_text)
        );
    }

    println!(
        "
== rows whose result differs between the two recognition modes (defaults)"
    );
    let a = decide_all(restricted, &base);
    let b = decide_all(unrestricted, &base);
    let mut differing = 0;
    for ((sample, x), y) in index.iter().zip(&a).zip(&b) {
        let vx = (x.class.value(), x.grand.value());
        let vy = (y.class.value(), y.grand.value());
        if vx != vy {
            differing += 1;
            println!(
                "  {} restricted text={:?} class={} grand={} | unrestricted text={:?} class={} grand={} | {}",
                sample.file,
                x.observation.text,
                show(vx.0),
                show(vx.1),
                y.observation.text,
                show(vy.0),
                show(vy.1),
                show_label(sample)
            );
        }
    }
    println!("  rows differing: {differing}");

    println!("\n== R3 sensitivity (defaults, restricted mode): text floor");
    for floor in [
        0.0, 0.3, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.93, 0.95, 0.97, 0.99,
    ] {
        let config = ScoreConfig {
            min_confidence: floor,
            ..base.clone()
        };
        let t = tally_with(index, restricted, &config, |_| true);
        println!("  floor {floor:.2}: {}", line_for(&t));
    }
    println!("\n== R3 sensitivity (defaults, restricted mode): per-character floor");
    for floor in [0.0, 0.3, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95, 0.97, 0.99] {
        let config = ScoreConfig {
            min_char_confidence: floor,
            ..base.clone()
        };
        let t = tally_with(index, restricted, &config, |_| true);
        println!("  char floor {floor:.2}: {}", line_for(&t));
    }

    println!(
        "\n== R3 cross-checks (restricted mode; the floor is derived on one part and applied to the other)"
    );
    type Part = (&'static str, fn(&Sample) -> bool);
    let pairs: [(Part, Part); 4] = [
        (
            ("known_miss=false", |s| !s.known_miss),
            ("known_miss=true", |s| s.known_miss),
        ),
        (
            ("known_miss=true", |s| s.known_miss),
            ("known_miss=false", |s| !s.known_miss),
        ),
        (("Jp", |s| s.server == "Jp"), ("Kr", |s| s.server == "Kr")),
        (("Kr", |s| s.server == "Kr"), ("Jp", |s| s.server == "Jp")),
    ];
    for ((from_name, from), (to_name, to)) in pairs {
        let s = split(index, restricted, &base, from);
        let text = derive_floor(&s.correct_text, &s.wrong_text);
        let character = derive_floor(&s.correct_char, &s.wrong_char);
        println!(
            "choose on {from_name} (correct n={}, wrong n={}): text floor {} char floor {}",
            s.correct_text.len(),
            s.wrong_text.len(),
            fmt_opt(text.value),
            fmt_opt(character.value)
        );
        let applied = ScoreConfig {
            min_confidence: text.value.unwrap_or(0.0),
            ..base.clone()
        };
        let reference = tally_with(index, restricted, &open, to);
        let t = tally_with(index, restricted, &applied, to);
        println!("   applied to {to_name} (text floor): {}", line_for(&t));
        println!(
            "   {to_name} with no floor:           {}",
            line_for(&reference)
        );
    }

    println!("\n== R2c clipping: rows whose result the check changes (restricted mode)");
    let on = decide_all(restricted, &base);
    let off = decide_all(
        restricted,
        &ScoreConfig {
            check_clipping: false,
            ..base.clone()
        },
    );
    let mut changed = 0;
    for ((sample, a), b) in index.iter().zip(&on).zip(&off) {
        let (von, voff) = (
            (a.class.value(), a.grand.value()),
            (b.class.value(), b.grand.value()),
        );
        if von != voff {
            changed += 1;
            println!(
                "  {} ({}) text={:?} conf={:.5} clip right={} vertical={} | without check class={} grand={} [{}/{}] | with check class={} grand={} [{}/{}] | {}",
                sample.file,
                sample.case,
                a.observation.text,
                a.observation.confidence,
                a.observation.clip.right,
                a.observation.clip.vertical,
                show(voff.0),
                show(voff.1),
                class_verdict(sample, voff.0).tag(),
                grand_verdict(sample, voff.1).tag(),
                show(von.0),
                show(von.1),
                class_verdict(sample, von.0).tag(),
                grand_verdict(sample, von.1).tag(),
                show_label(sample)
            );
        }
    }
    println!("  rows changed: {changed}");
    let right = restricted.rows.iter().filter(|o| o.clip.right).count();
    let vertical = restricted.rows.iter().filter(|o| o.clip.vertical).count();
    println!(
        "  rows flagged: right {right}, vertical {vertical} of {}",
        index.len()
    );
    println!("  flagged rows (any text):");
    for (sample, o) in index.iter().zip(&restricted.rows) {
        if o.clip.right || o.clip.vertical {
            println!(
                "    {} ({}) right={} vertical={} text={:?} | {}",
                sample.file,
                sample.case,
                o.clip.right,
                o.clip.vertical,
                o.text,
                show_label(sample)
            );
        }
    }

    println!("\n== R2b range policy (restricted mode, defaults otherwise)");
    for policy in [RangePolicy::Independent, RangePolicy::Joint] {
        let config = ScoreConfig {
            range_policy: policy,
            ..base.clone()
        };
        let t = tally_with(index, restricted, &config, |_| true);
        println!("  {policy:?}: {}", line_for(&t));
    }
    let range_rows: Vec<_> = index
        .iter()
        .zip(&decide_all(restricted, &base))
        .filter(|(_, s)| {
            s.class.reason() == Some(Reason::Range) || s.grand.reason() == Some(Reason::Range)
        })
        .map(|(sample, s)| {
            format!(
                "{} text={:?} | {}",
                sample.file,
                s.observation.text,
                show_label(sample)
            )
        })
        .collect();
    println!("  rows with an out-of-range number: {}", range_rows.len());
    for line in range_rows {
        println!("    {line}");
    }

    println!("\n== R2e character restriction (each mode with its own rule-derived floor)");
    for (mode, observed) in [("restricted", restricted), ("unrestricted", unrestricted)] {
        let s = split(index, observed, &base, |_| true);
        let floor = derive_floor(&s.correct_text, &s.wrong_text)
            .value
            .unwrap_or(0.0);
        let config = ScoreConfig {
            min_confidence: floor,
            ..base.clone()
        };
        let t = tally_with(index, observed, &config, |_| true);
        let mut times = observed.micros.clone();
        times.sort_unstable();
        println!(
            "  {mode} (floor {floor:.2}): {} | median {} us",
            line_for(&t),
            times.get(times.len() / 2).copied().unwrap_or(0)
        );
    }

    println!(
        "\n== edge ink in the raw crops (outline pixel <= 75 in rows 8..28 or the edge row/column)"
    );
    println!("  see the notes in docs/agents/rust-native.md; counted by the analysis helper below");
    edge_ink(index);
}

/// Counts, over the whole set, how many crops carry dark outline pixels on each edge of the crop.
fn edge_ink(index: &[Sample]) {
    // The crops are read again here so the counts do not depend on the reader.
    let dir = std::env::var_os("FGA_OCR_SAMPLES").map(PathBuf::from);
    let Some(dir) = dir else {
        println!("  FGA_OCR_SAMPLES is not set: edge counts skipped");
        return;
    };
    let mut counts = [0usize; 4];
    let mut positive = [0usize; 4];
    for sample in index {
        let Ok(image) = samples::load_gray(&dir.join(&sample.file)) else {
            continue;
        };
        let (w, h) = (image.width(), image.height());
        let px = |x: usize, y: usize| image.pixels().get(y * w + x).copied().unwrap_or(255);
        let content = (0..w)
            .rev()
            .find(|&x| (0..h).any(|y| px(x, y) > 0))
            .map_or(0, |x| x + 1);
        let band = (h * 4 / 15)..(h * 14 / 15);
        let left = band.clone().any(|y| px(0, y) <= 75);
        let right = content > 0 && band.clone().any(|y| px(content - 1, y) <= 75);
        let top = (0..content).any(|x| px(x, 0) <= 75);
        let bottom = (0..content).any(|x| px(x, h - 1) <= 75);
        let labelled =
            sample.is62 == Some(true) || sample.is16 == Some(true) || sample.cls.is_some();
        for (i, hit) in [left, right, top, bottom].into_iter().enumerate() {
            if hit {
                if let Some(c) = counts.get_mut(i) {
                    *c += 1;
                }
                if labelled && let Some(p) = positive.get_mut(i) {
                    *p += 1;
                }
            }
        }
    }
    for (i, name) in ["left", "right", "top", "bottom"].iter().enumerate() {
        println!(
            "  {name}: {} of {} crops, {} of them labelled readable (+62, +16 or a class value)",
            counts.get(i).copied().unwrap_or(0),
            index.len(),
            positive.get(i).copied().unwrap_or(0)
        );
    }
}
