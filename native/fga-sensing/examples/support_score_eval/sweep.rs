//! The cut sweep: SYNTHETIC cuts made from the real labelled crops, to see what the reader returns
//! when the field is cut by the crop, which the 370 real samples hardly contain.
//!
//! Each crop of a sample with a readable label is shifted inside the same 60x30 size, right and
//! left by 1 to 20 columns and down and up by 1 to 10 rows. What moves in from outside is not
//! real game content: the vacated columns are filled with the median gray of their row, the
//! vacated rows with the median gray of their column, which looks like panel background without
//! texture of its own. What moves out is lost, as in a real cut. Real cuts also lose the content
//! in a way a shift does not (the capture edge, the list scrolling), so a clean sweep is
//! necessary evidence, not proof.

use std::collections::BTreeMap;
use std::path::Path;

use fga_sensing::image::GrayImage;
use fga_sensing::readers::support_score::{
    ClipReport, Format, Observation, ScoreConfig, SupportScore, SupportScoreReader, decide,
};

use super::wrong::{self, Expect, classify};
use super::{Backend, Recognizer, Sample, Verdict, class_verdict, grand_verdict, samples};

pub const MAX_SHIFT_X: isize = 20;
pub const MAX_SHIFT_Y: isize = 10;

/// Dark outline pixels of the game's score text.
const INK_DARK: u8 = 75;

pub fn direction(dx: isize, dy: isize) -> &'static str {
    match (dx.signum(), dy.signum()) {
        (1, 0) => "right",
        (-1, 0) => "left",
        (0, 1) => "down",
        (0, -1) => "up",
        _ => "none",
    }
}

fn shift_list() -> Vec<(isize, isize)> {
    let mut list = vec![(0, 0)];
    for d in 1..=MAX_SHIFT_X {
        list.push((d, 0));
        list.push((-d, 0));
    }
    for d in 1..=MAX_SHIFT_Y {
        list.push((0, d));
        list.push((0, -d));
    }
    list
}

/// Where the text's ink is. Ink is a pixel at or below 75 (the dark outline) inside the text
/// band, the rows from four to fourteen fifteenths of the height; a column counts with at least
/// two such pixels and a row (from the band top down) with at least three, so a speckle is not
/// ink. The content ends where the black columns outside the game screen begin.
#[derive(Debug, Clone, Copy, Default)]
pub struct Ink {
    pub first_col: Option<usize>,
    pub last_col: Option<usize>,
    pub first_row: Option<usize>,
    pub last_row: Option<usize>,
    pub content: usize,
    pub height: usize,
}

pub fn ink_of(image: &GrayImage) -> Ink {
    let (w, h) = (image.width(), image.height());
    let px = |x: usize, y: usize| image.pixels().get(y * w + x).copied().unwrap_or(255);
    let content = (0..w)
        .rev()
        .find(|&x| (0..h).any(|y| px(x, y) > 0))
        .map_or(0, |x| x + 1);
    let top = h * 4 / 15;
    let bottom = h * 14 / 15;
    let cols: Vec<usize> = (0..content)
        .filter(|&x| (top..bottom).filter(|&y| px(x, y) <= INK_DARK).count() >= 2)
        .collect();
    let rows: Vec<usize> = (top..h)
        .filter(|&y| (0..content).filter(|&x| px(x, y) <= INK_DARK).count() >= 3)
        .collect();
    Ink {
        first_col: cols.first().copied(),
        last_col: cols.last().copied(),
        first_row: rows.first().copied(),
        last_row: rows.last().copied(),
        content,
        height: h,
    }
}

pub struct CutRow {
    pub sample: usize,
    pub dx: isize,
    pub dy: isize,
    pub obs: Observation,
    pub ink: Ink,
}

pub fn has_readable_label(s: &Sample) -> bool {
    s.is62 == Some(true) || s.is16 == Some(true) || s.cls.is_some() || s.grand.is_some()
}

/// Reads every crop at shift (0, 0) and, for the samples with a readable label, at every shift.
pub fn run<B: Backend>(
    args: &super::Args,
    index: &[Sample],
    load: &(impl Fn(&Path) -> Result<B, fga_sensing::Error> + Sync),
    config: &ScoreConfig,
) -> Result<Vec<CutRow>, String> {
    let workers = std::thread::available_parallelism()
        .map_or(2, |n| n.get())
        .clamp(1, 8);
    let shifts = shift_list();
    let results: Vec<Result<Vec<CutRow>, String>> = std::thread::scope(|scope| {
        let handles: Vec<_> = (0..workers)
            .map(|worker| {
                let shifts = &shifts;
                scope.spawn(move || -> Result<Vec<CutRow>, String> {
                    let backend = load(&args.model).map_err(|e| e.to_string())?;
                    let recognizer = Recognizer::new(backend).map_err(|e| e.to_string())?;
                    let mut reader = SupportScoreReader::new(recognizer, config.clone())
                        .map_err(|e| e.to_string())?;
                    let mut rows = Vec::new();
                    for (i, sample) in index.iter().enumerate() {
                        if i % workers != worker {
                            continue;
                        }
                        let crop = samples::load_gray(&args.samples.join(&sample.file))?;
                        for &(dx, dy) in shifts {
                            if (dx, dy) != (0, 0) && !has_readable_label(sample) {
                                continue;
                            }
                            let image = samples::shifted(&crop, dx, dy)?;
                            let obs = reader
                                .observe(&image)
                                .map_err(|e| format!("{}: {e}", sample.file))?;
                            rows.push(CutRow {
                                sample: i,
                                dx,
                                dy,
                                obs,
                                ink: ink_of(&image),
                            });
                        }
                    }
                    Ok(rows)
                })
            })
            .collect();
        handles
            .into_iter()
            .map(|h| h.join().unwrap_or_else(|_| Err("worker panicked".into())))
            .collect()
    });
    let mut all = Vec::new();
    for part in results {
        all.extend(part?);
    }
    all.sort_by_key(|r| (r.sample, r.dx, r.dy));
    Ok(all)
}

/// Wrong by the one definition of `wrong::classify`.
fn is_wrong(sample: &Sample, expect: &Expect, score: &SupportScore) -> bool {
    classify(
        sample,
        expect.truth,
        expect.no_grand,
        score.class.value(),
        score.grand.value(),
    )
    .wrong()
        > 0
}

/// What the unshifted read of a sample (when there is one) lets the cut reads be judged against.
fn expectation(sample: &Sample, base: Option<&&SupportScore>) -> Expect {
    wrong::expect(
        sample,
        base.map(|b| wrong::BaseRead {
            class: b.class.value(),
            grand: b.grand.value(),
            ordinary: b.display == fga_sensing::readers::support_score::Display::Ordinary,
        }),
    )
}

fn expectation_none() -> Expect {
    Expect {
        truth: (None, None),
        no_grand: false,
    }
}

fn has_value(score: &SupportScore) -> bool {
    score.class.value().is_some() || score.grand.value().is_some()
}

fn range_of(values: impl Iterator<Item = usize>) -> Option<(usize, usize)> {
    values.fold(None, |acc, v| match acc {
        None => Some((v, v)),
        Some((lo, hi)) => Some((lo.min(v), hi.max(v))),
    })
}

fn histogram(title: &str, values: impl Iterator<Item = Option<usize>>) {
    let mut counts: BTreeMap<String, usize> = BTreeMap::new();
    for v in values {
        *counts
            .entry(v.map_or("none".to_string(), |v| format!("{v:02}")))
            .or_default() += 1;
    }
    let line: Vec<String> = counts.iter().map(|(k, v)| format!("{k}:{v}")).collect();
    println!("  {title}: {}", line.join(" "));
}

#[derive(Clone, Copy, Debug)]
enum Right {
    None,
    Ink(usize),
    Range(usize),
}

#[derive(Clone, Copy, Debug)]
enum Left {
    None,
    Ink(usize),
    Range(usize),
}

#[derive(Clone, Copy, Debug)]
enum Vert {
    None,
    InkBottom(usize),
    Range(usize),
    Current,
}

#[derive(Clone, Copy, Debug)]
struct Combo(Right, Left, Vert);

impl Combo {
    fn parts(&self) -> usize {
        usize::from(!matches!(self.0, Right::None))
            + usize::from(!matches!(self.1, Left::None))
            + usize::from(!matches!(self.2, Vert::None))
    }

    fn name(&self) -> String {
        format!("right={:?} left={:?} vertical={:?}", self.0, self.1, self.2)
    }
}

struct Ranges {
    last_col: (usize, usize),
    first_col: (usize, usize),
    first_row: (usize, usize),
    last_row: (usize, usize),
}

fn outside(value: Option<usize>, range: (usize, usize), margin: usize) -> bool {
    value.is_some_and(|v| v + margin < range.0 || v > range.1 + margin)
}

fn cut(row: &CutRow, combo: Combo, ranges: &Ranges) -> bool {
    let ink = &row.ink;
    let right = match combo.0 {
        Right::None => false,
        Right::Ink(k) => ink.last_col.is_some_and(|c| c + k >= ink.content),
        Right::Range(m) => outside(ink.last_col, ranges.last_col, m),
    };
    let left = match combo.1 {
        Left::None => false,
        Left::Ink(k) => ink.first_col.is_some_and(|c| c < k),
        Left::Range(m) => outside(ink.first_col, ranges.first_col, m),
    };
    let vertical = match combo.2 {
        Vert::None => false,
        Vert::InkBottom(k) => ink.last_row.is_some_and(|r| r + k >= ink.height),
        Vert::Range(m) => {
            outside(ink.first_row, ranges.first_row, m) || outside(ink.last_row, ranges.last_row, m)
        }
        Vert::Current => row.obs.clip.vertical,
    };
    right || left || vertical
}

pub fn report(
    index: &[Sample],
    rows: &[CutRow],
    config: &ScoreConfig,
    output: &Path,
) -> Result<(), String> {
    // The reader without any pixel rule, as the baseline every candidate is applied to.
    let bare = ScoreConfig {
        check_clipping: false,
        ..config.clone()
    };
    let base_scores: Vec<SupportScore> =
        rows.iter().map(|r| decide(r.obs.clone(), &bare)).collect();
    // The unshifted reader result of each sample, for the truth of unlabelled fields.
    let mut unshifted: BTreeMap<usize, &SupportScore> = BTreeMap::new();
    for (row, score) in rows.iter().zip(&base_scores) {
        if (row.dx, row.dy) == (0, 0) {
            unshifted.insert(row.sample, score);
        }
    }
    let expects: Vec<Expect> = rows
        .iter()
        .map(|r| match index.get(r.sample) {
            Some(sample) => expectation(sample, unshifted.get(&r.sample)),
            None => expectation_none(),
        })
        .collect();
    let sample_of = |r: &CutRow| index.get(r.sample);

    println!(
        "config: format={:?} floor={} char_floor={} restricted={}",
        config.format,
        config.min_confidence,
        config.min_char_confidence,
        config.restrict_characters
    );
    let sweep_samples = index.iter().filter(|s| has_readable_label(s)).count();
    println!(
        "sweep: {sweep_samples} samples with a readable label x {} shifts (right/left 1..={MAX_SHIFT_X}, down/up 1..={MAX_SHIFT_Y}) plus all {} real crops unshifted. SYNTHETIC: vacated area = median gray of its row (columns) or column (rows).",
        shift_list().len() - 1,
        index.len()
    );

    // ---- Real labelled reads: where the ink ends.
    let ok_real: Vec<usize> = rows
        .iter()
        .enumerate()
        .filter(|(i, r)| {
            (r.dx, r.dy) == (0, 0)
                && sample_of(r).is_some_and(|s| {
                    has_readable_label(s)
                        && base_scores.get(*i).is_some_and(|b| {
                            class_verdict(s, b.class.value()) == Verdict::Ok
                                || grand_verdict(s, b.grand.value()) == Verdict::Ok
                        })
                })
        })
        .map(|(i, _)| i)
        .collect();
    let ink_of_rows = |f: fn(&Ink) -> Option<usize>| -> Vec<Option<usize>> {
        ok_real
            .iter()
            .filter_map(|&i| rows.get(i))
            .map(|r| f(&r.ink))
            .collect()
    };
    println!(
        "\n== where the ink ends, {} real labelled reads (unshifted)",
        ok_real.len()
    );
    histogram("last ink column", ink_of_rows(|i| i.last_col).into_iter());
    histogram(
        "last ink column counted from the end of the content (content - 1 - last)",
        ok_real.iter().filter_map(|&i| rows.get(i)).map(|r| {
            r.ink
                .last_col
                .map(|c| r.ink.content.saturating_sub(1).saturating_sub(c))
        }),
    );
    histogram("first ink column", ink_of_rows(|i| i.first_col).into_iter());
    histogram("first ink row", ink_of_rows(|i| i.first_row).into_iter());
    histogram("last ink row", ink_of_rows(|i| i.last_row).into_iter());
    histogram(
        "content width",
        ok_real
            .iter()
            .filter_map(|&i| rows.get(i))
            .map(|r| Some(r.ink.content)),
    );
    let obs_range = |f: fn(&Ink) -> Option<usize>| -> (usize, usize) {
        range_of(ink_of_rows(f).into_iter().flatten()).unwrap_or((0, 0))
    };
    let ranges = Ranges {
        last_col: obs_range(|i| i.last_col),
        first_col: obs_range(|i| i.first_col),
        first_row: obs_range(|i| i.first_row),
        last_row: obs_range(|i| i.last_row),
    };
    println!(
        "  observed ranges: last col {:?}, first col {:?}, first row {:?}, last row {:?}",
        ranges.last_col, ranges.first_col, ranges.first_row, ranges.last_row
    );

    // ---- Sweep with the reader as it is (the lib's own pixel rules).
    println!(
        "\n== sweep of the reader as built (its own pixel rules), values returned / WRONG per amount"
    );
    let actual: Vec<SupportScore> = rows.iter().map(|r| decide(r.obs.clone(), config)).collect();
    print_sweep(index, rows, &actual, &expects);

    // ---- Same without any pixel rule.
    println!("\n== sweep with no pixel rule at all");
    print_sweep(index, rows, &base_scores, &expects);

    let table_a = class_report(index, rows, &base_scores, config);

    // The rows are those of rule set A, the first table above, whatever the command line says
    // about the format or the pixel check: the file must add up to that table.
    let rule_set_a = ScoreConfig {
        format: Format::Strict,
        check_clipping: true,
        ..config.clone()
    };
    let a_scores: Vec<SupportScore> = rows
        .iter()
        .map(|r| decide(r.obs.clone(), &rule_set_a))
        .collect();
    let written = cut_rows_text(index, rows, &a_scores, &expects);
    std::fs::write(output, &written).map_err(|e| format!("{}: {e}", output.display()))?;
    let summed = wrong::sum_rows(&written)?;
    for dir in ["right", "left", "down", "up"] {
        let from_file = summed.get(dir).copied().unwrap_or_default();
        let from_table = table_a.get(dir).copied().unwrap_or_default();
        if from_file != from_table {
            return Err(format!(
                "the rows written to {} add up to {from_file:?} for {dir} (F62, F16, OTHER, correct, no-truth), the rule-set A table says {from_table:?}",
                output.display()
            ));
        }
    }
    println!(
        "  the {} rows written to {} add up to the rule-set A table, per direction",
        written.lines().count().saturating_sub(1),
        output.display()
    );

    // ---- Candidates.
    println!("\n== candidate rules (offline, on the cached observations)");
    let margin = 3;
    let rights = [
        Right::None,
        Right::Ink(1),
        Right::Ink(2),
        Right::Ink(3),
        Right::Ink(4),
        Right::Range(margin),
    ];
    let lefts = [
        Left::None,
        Left::Ink(1),
        Left::Ink(2),
        Left::Ink(3),
        Left::Ink(4),
        Left::Range(margin),
    ];
    let verts = [
        Vert::None,
        Vert::InkBottom(1),
        Vert::InkBottom(2),
        Vert::InkBottom(3),
        Vert::InkBottom(4),
        Vert::Range(margin),
        Vert::Current,
    ];
    struct Outcome {
        combo: Combo,
        lost: Vec<String>,
        wrong: BTreeMap<&'static str, usize>,
        wrong_total: usize,
    }
    let mut outcomes = Vec::new();
    for &r in &rights {
        for &l in &lefts {
            for &v in &verts {
                let combo = Combo(r, l, v);
                let mut lost = Vec::new();
                let mut wrong: BTreeMap<&'static str, usize> = BTreeMap::new();
                for (i, row) in rows.iter().enumerate() {
                    let Some(sample) = sample_of(row) else {
                        continue;
                    };
                    let Some(base) = base_scores.get(i) else {
                        continue;
                    };
                    let is_cut = cut(row, combo, &ranges);
                    let e = expects.get(i).copied().unwrap_or_else(expectation_none);
                    let dir = direction(row.dx, row.dy);
                    if (row.dx, row.dy) == (0, 0) {
                        let had_ok = class_verdict(sample, base.class.value()) == Verdict::Ok
                            || grand_verdict(sample, base.grand.value()) == Verdict::Ok;
                        if is_cut && had_ok {
                            lost.push(sample.file.clone());
                        }
                        let wrong_real = !is_cut
                            && (class_verdict(sample, base.class.value()) == Verdict::Wrong
                                || grand_verdict(sample, base.grand.value()) == Verdict::Wrong);
                        if wrong_real {
                            *wrong.entry("real").or_default() += 1;
                        }
                    } else if !is_cut && has_value(base) && is_wrong(sample, &e, base) {
                        *wrong.entry(dir).or_default() += 1;
                    }
                }
                let wrong_total = wrong.values().sum();
                outcomes.push(Outcome {
                    combo,
                    lost,
                    wrong,
                    wrong_total,
                });
            }
        }
    }
    let show_outcome = |o: &Outcome| {
        println!(
            "  {} | lost {} | wrong: real {} right {} left {} down {} up {}",
            o.combo.name(),
            o.lost.len(),
            o.wrong.get("real").copied().unwrap_or(0),
            o.wrong.get("right").copied().unwrap_or(0),
            o.wrong.get("left").copied().unwrap_or(0),
            o.wrong.get("down").copied().unwrap_or(0),
            o.wrong.get("up").copied().unwrap_or(0)
        );
    };
    println!("single-end candidates (the other ends off):");
    for o in outcomes.iter().filter(|o| o.combo.parts() <= 1) {
        show_outcome(o);
        if !o.lost.is_empty() {
            println!("      lost files: {}", o.lost.join(" "));
        }
    }
    let mut zero: Vec<&Outcome> = outcomes.iter().filter(|o| o.wrong_total == 0).collect();
    zero.sort_by_key(|o| (o.lost.len(), o.combo.parts()));
    println!(
        "\ncombinations with zero wrong values everywhere: {}",
        zero.len()
    );
    for o in zero.iter().take(12) {
        show_outcome(o);
        if !o.lost.is_empty() {
            println!("      lost files: {}", o.lost.join(" "));
        }
    }
    match zero.first() {
        Some(best) => {
            println!(
                "SELECTED by the rule (zero wrong, fewest lost, then fewest rules): {}",
                best.combo.name()
            );
        }
        None => {
            println!("NO CANDIDATE reaches zero wrong values; nothing is selected. Least wrong:");
            let mut all: Vec<&Outcome> = outcomes.iter().collect();
            all.sort_by_key(|o| (o.wrong_total, o.lost.len(), o.combo.parts()));
            for o in all.iter().take(8) {
                show_outcome(o);
            }
        }
    }
    Ok(())
}

fn print_sweep(index: &[Sample], rows: &[CutRow], scores: &[SupportScore], expects: &[Expect]) {
    // direction -> amount -> (values, wrong)
    let mut table: BTreeMap<(&'static str, isize), (usize, usize)> = BTreeMap::new();
    let mut wrongs: Vec<String> = Vec::new();
    for ((row, score), e) in rows.iter().zip(scores).zip(expects) {
        if (row.dx, row.dy) == (0, 0) {
            continue;
        }
        let Some(sample) = index.get(row.sample) else {
            continue;
        };
        let dir = direction(row.dx, row.dy);
        let amount = row.dx.abs().max(row.dy.abs());
        let entry = table.entry((dir, amount)).or_default();
        entry.0 += usize::from(has_value(score));
        if is_wrong(sample, e, score) {
            entry.1 += 1;
            wrongs.push(format!(
                "{} {dir} {amount}: text {:?} conf {:.5} -> class {:?} grand {:?} (truth {:?})",
                sample.file,
                score.observation.text,
                score.observation.confidence,
                score.class.value(),
                score.grand.value(),
                e.truth
            ));
        }
    }
    for dir in ["right", "left", "down", "up"] {
        let line: Vec<String> = table
            .iter()
            .filter(|((d, _), _)| *d == dir)
            .map(|((_, a), (v, w))| format!("{a}:{v}/{w}"))
            .collect();
        let wrong: usize = table
            .iter()
            .filter(|((d, _), _)| *d == dir)
            .map(|(_, (_, w))| w)
            .sum();
        println!("  {dir:5} WRONG total {wrong}: {}", line.join(" "));
    }
    println!("  wrong values listed ({}):", wrongs.len());
    for w in &wrongs {
        println!("    {w}");
    }
}

/// One configuration of the class split: a format, the pixel rules as ink columns (`right`: the
/// last k columns of the content, `left`: the first column); the flat-bottom rule is always on.
struct Variant {
    name: &'static str,
    format: Format,
    right: Option<usize>,
    left: bool,
}

#[derive(Default, Clone, Copy)]
struct Split {
    f62: usize,
    f16: usize,
    other: usize,
    correct: usize,
    no_truth: usize,
}

/// Splits the wrong values of the sweep by what the automation does with them: the selection acts
/// only on class 62 and a compound's Grand 16, so a wrong 62 (F62) or wrong 16 (F16) is a wrong
/// pick, and any other wrong number is a miss. Correct accepts on cut crops are the benefit side.
pub fn class_report(
    index: &[Sample],
    rows: &[CutRow],
    base_scores: &[SupportScore],
    config: &ScoreConfig,
) -> BTreeMap<&'static str, [usize; 5]> {
    let mut table_a: BTreeMap<&'static str, [usize; 5]> = BTreeMap::new();
    let variants = [
        Variant {
            name: "A strict, flat-bottom, no ink rule",
            format: Format::Strict,
            right: None,
            left: false,
        },
        Variant {
            name: "B A + ink in the last 1 column",
            format: Format::Strict,
            right: Some(1),
            left: false,
        },
        Variant {
            name: "C A + ink in the last 2 columns",
            format: Format::Strict,
            right: Some(2),
            left: false,
        },
        Variant {
            name: "D last 4 columns + first column",
            format: Format::Strict,
            right: Some(4),
            left: true,
        },
        Variant {
            name: "E as A with CompoundClassPlusOptional",
            format: Format::CompoundClassPlusOptional,
            right: None,
            left: false,
        },
    ];
    let mut unshifted: BTreeMap<usize, &SupportScore> = BTreeMap::new();
    for (row, score) in rows.iter().zip(base_scores) {
        if (row.dx, row.dy) == (0, 0) {
            unshifted.insert(row.sample, score);
        }
    }
    let cut_crops = rows.iter().filter(|r| (r.dx, r.dy) != (0, 0)).count();
    println!(
        "\n== the wrong values of the cut sweep by what the automation does with them ({cut_crops} cut crops examined)"
    );
    println!(
        "F62: class 62 returned for a true class that is not 62. F16: Grand 16 returned for a true Grand that is not 16 (or a field with none). OTHER: any other wrong number (any Grand value on a field with none is wrong). correct: 62 or 16 returned that is the true value. no-truth: 62 or 16 returned for a field nothing is known about."
    );
    for v in &variants {
        // The comparison is of rule sets, so the command line cannot switch the pixel check off.
        let cfg = ScoreConfig {
            format: v.format,
            check_clipping: true,
            ..config.clone()
        };
        let verdicts: Vec<SupportScore> = rows
            .iter()
            .map(|r| {
                let cut = r.obs.clip.vertical
                    || v.right
                        .is_some_and(|k| r.ink.last_col.is_some_and(|c| c + k >= r.ink.content))
                    || (v.left && r.ink.first_col.is_some_and(|c| c < 1));
                let mut obs = r.obs.clone();
                obs.clip = ClipReport { vertical: cut };
                decide(obs, &cfg)
            })
            .collect();
        let real: Vec<&SupportScore> = rows
            .iter()
            .zip(&verdicts)
            .filter(|(r, _)| (r.dx, r.dy) == (0, 0))
            .map(|(_, s)| s)
            .collect();
        let t = super::tally(index.iter().zip(real.iter().copied()));
        println!("\n-- {}", v.name);
        println!(
            "  real 370: +62 missed {} of {}, +16 missed {} of {}, false accepts {}, wrong values {}, class values equal {} of {}",
            t.positives62 - t.read62,
            t.positives62,
            t.positives16 - t.read16,
            t.positives16,
            t.false_accepts62 + t.false_accepts16,
            t.wrong(),
            t.class_equal,
            t.class_labelled
        );
        let mut per: BTreeMap<(&'static str, isize), Split> = BTreeMap::new();
        let mut listed: Vec<String> = Vec::new();
        let mut no_truth_files: BTreeMap<String, usize> = BTreeMap::new();
        for (row, score) in rows.iter().zip(&verdicts) {
            if (row.dx, row.dy) == (0, 0) {
                continue;
            }
            let Some(sample) = index.get(row.sample) else {
                continue;
            };
            let base = unshifted.get(&row.sample);
            let e = expectation(sample, base);
            let dir = direction(row.dx, row.dy);
            let amount = row.dx.abs().max(row.dy.abs());
            let entry = per.entry((dir, amount)).or_default();
            let class = score.class.value();
            let grand = score.grand.value();
            let kinds = classify(sample, e.truth, e.no_grand, class, grand);
            let (f62, f16) = (kinds.f62 > 0, kinds.f16 > 0);
            entry.f62 += kinds.f62;
            entry.f16 += kinds.f16;
            entry.other += kinds.other;
            entry.correct += kinds.correct;
            entry.no_truth += kinds.no_truth;
            if kinds.no_truth > 0 {
                *no_truth_files.entry(sample.file.clone()).or_default() += kinds.no_truth;
            }
            if f62 || f16 {
                listed.push(format!(
                    "    {} {dir} {amount}: text {:?} conf {:.5} weakest char {:?} -> class {:?} grand {:?} ({})",
                    sample.file,
                    score.observation.text,
                    score.observation.confidence,
                    score.observation.min_char_confidence,
                    class,
                    grand,
                    match (f62, f16) {
                        (true, true) => "F62 F16",
                        (true, false) => "F62",
                        _ => "F16",
                    }
                ));
            }
        }
        for dir in ["right", "left", "down", "up"] {
            let sum =
                per.iter()
                    .filter(|((d, _), _)| *d == dir)
                    .fold(Split::default(), |a, (_, s)| Split {
                        f62: a.f62 + s.f62,
                        f16: a.f16 + s.f16,
                        other: a.other + s.other,
                        correct: a.correct + s.correct,
                        no_truth: a.no_truth + s.no_truth,
                    });
            let by_shift: Vec<String> = per
                .iter()
                .filter(|((d, _), s)| *d == dir && (s.f62 + s.f16 + s.other) > 0)
                .map(|((_, a), s)| format!("{a}:{}/{}/{}", s.f62, s.f16, s.other))
                .collect();
            if v.name.starts_with("A ") {
                table_a.insert(
                    dir,
                    [sum.f62, sum.f16, sum.other, sum.correct, sum.no_truth],
                );
            }
            println!(
                "  {dir:5} F62 {} F16 {} OTHER {} correct accepts {} no-truth {} | nonzero shifts (F62/F16/OTHER): {}",
                sum.f62,
                sum.f16,
                sum.other,
                sum.correct,
                sum.no_truth,
                by_shift.join(" ")
            );
        }
        println!(
            "  no truth (a 62 or 16 read with no label, no flag and no unshifted read): {} fields in {} files {:?}",
            no_truth_files.values().sum::<usize>(),
            no_truth_files.len(),
            no_truth_files
        );
        println!("  F62/F16 listed ({}):", listed.len());
        for l in &listed {
            println!("{l}");
        }
    }
    table_a
}

/// One row per cut crop: the sample, the shift, what was read, and the five counts of
/// `wrong::Kinds` as numbers (f62, f16, other, correct, no_truth), so the file can be summed. The
/// column positions are `wrong::DIRECTION_COLUMN` and `wrong::COUNT_COLUMN`.
fn cut_rows_text(
    index: &[Sample],
    rows: &[CutRow],
    scores: &[SupportScore],
    expects: &[Expect],
) -> String {
    let mut out = String::from(
        "file\tdirection\tamount\ttext\tconfidence\tmin_char\tclass\tgrand\tf62\tf16\tother\tcorrect\tno_truth\n",
    );
    let show = |v: Option<u8>| v.map_or(String::new(), |v| v.to_string());
    for ((row, score), e) in rows.iter().zip(scores).zip(expects) {
        if (row.dx, row.dy) == (0, 0) {
            continue;
        }
        let Some(sample) = index.get(row.sample) else {
            continue;
        };
        let class = score.class.value();
        let grand = score.grand.value();
        let k = classify(sample, e.truth, e.no_grand, class, grand);
        let [f62, f16, other, correct, no_truth] = wrong::counts(&k);
        out.push_str(&format!(
            "{}\t{}\t{}\t{}\t{:.5}\t{}\t{}\t{}\t{f62}\t{f16}\t{other}\t{correct}\t{no_truth}\n",
            sample.file,
            direction(row.dx, row.dy),
            row.dx.abs().max(row.dy.abs()),
            score.observation.text,
            score.observation.confidence,
            score
                .observation
                .min_char_confidence
                .map_or(String::new(), |c| format!("{c:.5}")),
            show(class),
            show(grand),
        ));
    }
    out
}
