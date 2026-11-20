//! Runs the recognition model over the local support-score samples and compares the result with
//! the Python check of docs/qa/0025-stage-0-baseline.md, section 4.5.
//!
//! cargo run --release -p fga-sensing --features tract --example ocr_eval -- \
//!     tract <model.onnx> <samples dir> <output.tsv> [--allowed +/0123456789] [--min-width 320] \
//!     [--threads 1]
//!
//! `--min-width` and `--threads` are the measurements of issue #549; `--threads` above 1 needs
//! `--features tract-threads`.

#[path = "../tests/support/samples.rs"]
mod samples;

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::{Duration, Instant};

use fga_sensing::Recognizer;
use fga_sensing::backend::Backend;
use fga_sensing::preprocess::MIN_MODEL_WIDTH;
use samples::Sample;

struct Args {
    backend: String,
    model: PathBuf,
    samples: PathBuf,
    output: PathBuf,
    allowed: Option<String>,
    min_width: usize,
    threads: Option<usize>,
}

struct Row<'a> {
    sample: &'a Sample,
    text: String,
    confidence: f64,
    elapsed: Duration,
}

fn main() -> ExitCode {
    match parse_args().and_then(|args| run(&args)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("ocr_eval: {message}");
            ExitCode::FAILURE
        }
    }
}

fn parse_args() -> Result<Args, String> {
    let usage = "usage: ocr_eval <tract|ort> <model.onnx> <samples dir> <output file> \
                 [--allowed <chars>] [--min-width <columns>] [--threads <count>]";
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
        allowed: None,
        min_width: MIN_MODEL_WIDTH,
        threads: None,
    };
    let number = |text: String| text.parse::<usize>().map_err(|_| usage.to_string());
    while let Ok(flag) = next() {
        match flag.as_str() {
            "--allowed" => parsed.allowed = Some(next()?),
            "--min-width" => parsed.min_width = number(next()?)?,
            "--threads" => parsed.threads = Some(number(next()?)?),
            _ => return Err(usage.to_string()),
        }
    }
    Ok(parsed)
}

fn run(args: &Args) -> Result<(), String> {
    match args.backend.as_str() {
        #[cfg(feature = "tract")]
        "tract" => {
            let settings = fga_sensing::backend::TractSettings {
                min_width: args.min_width,
                threads: args.threads.unwrap_or(1),
            };
            evaluate(args, |path| {
                fga_sensing::backend::TractBackend::load_file_with(path, settings)
            })
        }
        #[cfg(feature = "ort")]
        "ort" => {
            if args.threads.is_some() {
                return Err("--threads is for the tract backend".to_string());
            }
            evaluate(args, fga_sensing::backend::OrtBackend::load_file)
        }
        other => Err(format!(
            "backend {other:?} is not compiled in; build with --features {other}"
        )),
    }
}

fn evaluate<B: Backend>(
    args: &Args,
    load: impl FnOnce(&Path) -> Result<B, fga_sensing::Error>,
) -> Result<(), String> {
    let index = samples::load_index(&args.samples)?;
    let started = Instant::now();
    let backend = load(&args.model).map_err(|e| e.to_string())?;
    let load_time = started.elapsed();
    let mut recognizer = Recognizer::new(backend)
        .and_then(|recognizer| recognizer.with_min_width(args.min_width))
        .map_err(|e| e.to_string())?;
    let allowed = match &args.allowed {
        Some(chars) => Some(recognizer.allowed_set(chars).map_err(|e| e.to_string())?),
        None => None,
    };

    let mut rows = Vec::with_capacity(index.len());
    for sample in &index {
        let crop = samples::load_gray(&args.samples.join(&sample.file))?;
        let started = Instant::now();
        let decoded = recognizer
            .recognize(&crop, allowed.as_ref())
            .map_err(|e| format!("{}: {e}", sample.file))?;
        rows.push(Row {
            sample,
            text: decoded.text,
            confidence: decoded.confidence,
            elapsed: started.elapsed(),
        });
    }
    write_rows(&args.output, &rows)?;
    print_summary(args, &rows, load_time);
    Ok(())
}

fn write_rows(path: &Path, rows: &[Row]) -> Result<(), String> {
    let mut out = String::from("file\tcase\ttext\tconfidence\tms\tpy_text\tpy_conf\tsame\n");
    for row in rows {
        let _ = writeln!(
            out,
            "{}\t{}\t{}\t{:.5}\t{:.3}\t{}\t{:.5}\t{}",
            row.sample.file,
            row.sample.case,
            row.text,
            row.confidence,
            row.elapsed.as_secs_f64() * 1000.0,
            row.sample.py_text,
            row.sample.py_conf,
            row.text == row.sample.py_text
        );
    }
    std::fs::write(path, out).map_err(|e| format!("{}: {e}", path.display()))
}

/// (class score, grand score) as read from a text; `None` where the reading rule does not match.
type Scores = (Option<u32>, Option<u32>);

/// The Python check's reading rule: spaces removed, full-width + and / folded, then
/// `^\+?(\d{1,2})(?:/\+?(\d{1,2}))?$`. Returns (class score, grand score).
fn parse_score(text: &str) -> Scores {
    let text: String = text
        .chars()
        .filter(|&c| c != ' ')
        .map(|c| match c {
            '＋' => '+',
            '／' => '/',
            other => other,
        })
        .collect();
    let mut parts = text.splitn(2, '/');
    let first = parts.next().and_then(number);
    match (first, parts.next()) {
        (Some(class), None) => (Some(class), None),
        (Some(class), Some(rest)) => match number(rest) {
            Some(grand) => (Some(class), Some(grand)),
            None => (None, None),
        },
        (None, _) => (None, None),
    }
}

/// `\+?\d{1,2}` for the whole of `part`. Python's `\d` also takes full-width digits, which the
/// model can output, so they count here too.
fn number(part: &str) -> Option<u32> {
    let digits = part.strip_prefix('+').unwrap_or(part);
    let values: Option<Vec<u32>> = digits
        .chars()
        .map(|c| match c {
            '０'..='９' => Some(c as u32 - '０' as u32),
            _ => c.to_digit(10),
        })
        .collect();
    match values?.as_slice() {
        [ones] => Some(*ones),
        [tens, ones] => Some(tens * 10 + ones),
        _ => None,
    }
}

#[derive(Default)]
struct Tally {
    labelled: usize,
    positives: usize,
    misses: usize,
    false_accepts: usize,
}

fn tally<'a>(
    rows: impl Iterator<Item = (&'a Sample, &'a str)>,
    label: fn(&Sample) -> Option<bool>,
    value: fn(Scores) -> Option<u32>,
    wanted: u32,
) -> Tally {
    let mut tally = Tally::default();
    for (sample, text) in rows {
        let Some(truth) = label(sample) else { continue };
        let accepted = value(parse_score(text)) == Some(wanted);
        tally.labelled += 1;
        if truth {
            tally.positives += 1;
            tally.misses += usize::from(!accepted);
        } else {
            tally.false_accepts += usize::from(accepted);
        }
    }
    tally
}

type Question = (
    &'static str,
    fn(&Sample) -> Option<bool>,
    fn(Scores) -> Option<u32>,
    u32,
);

const QUESTIONS: [Question; 2] = [
    ("+62?", |s| s.is62, |(class, _)| class, 62),
    ("+16?", |s| s.is16, |(_, grand)| grand, 16),
];

fn print_summary(args: &Args, rows: &[Row], load_time: Duration) {
    let same = rows.iter().filter(|r| r.text == r.sample.py_text).count();
    println!(
        "backend={} allowed={} min_width={} threads={} samples={}",
        args.backend,
        args.allowed.as_deref().unwrap_or("-"),
        args.min_width,
        args.threads.map_or("-".to_string(), |t| t.to_string()),
        rows.len()
    );
    println!("same text as Python: {same}/{}", rows.len());

    for (name, label, value, wanted) in QUESTIONS {
        for (scope, only_known) in [("all", false), ("known_miss", true)] {
            let selected = || {
                rows.iter()
                    .filter(move |r| !only_known || r.sample.known_miss)
            };
            let ours = tally(
                selected().map(|r| (r.sample, r.text.as_str())),
                label,
                value,
                wanted,
            );
            let python = tally(
                selected().map(|r| (r.sample, r.sample.py_text.as_str())),
                label,
                value,
                wanted,
            );
            println!(
                "{name} {scope}: labelled={} positives={} | rust miss={} false_accept={} | \
                 python miss={} false_accept={}",
                ours.labelled,
                ours.positives,
                ours.misses,
                ours.false_accepts,
                python.misses,
                python.false_accepts
            );
        }
        for row in rows {
            let truth = label(row.sample);
            let ours = value(parse_score(&row.text)) == Some(wanted);
            let python = value(parse_score(&row.sample.py_text)) == Some(wanted);
            if truth == Some(false) && ours && !python {
                println!(
                    "{name} new false accept: {} ({}) text={:?}",
                    row.sample.file, row.sample.case, row.text
                );
            }
        }
    }

    let mut times: Vec<Duration> = rows.iter().map(|r| r.elapsed).collect();
    times.sort_unstable();
    let ms = |d: Option<&Duration>| d.map_or(0.0, |d| d.as_secs_f64() * 1000.0);
    let half = times.len() / 2;
    let median = if times.len().is_multiple_of(2) {
        (ms(half.checked_sub(1).and_then(|i| times.get(i))) + ms(times.get(half))) / 2.0
    } else {
        ms(times.get(half))
    };
    println!(
        "time per crop: median={:.2}ms max={:.2}ms first={:.2}ms | model load={:.0}ms",
        median,
        ms(times.last()),
        ms(rows.first().map(|r| &r.elapsed)),
        load_time.as_secs_f64() * 1000.0
    );
    // Confidence over the samples whose text reads as a score: how close a narrower input comes
    // to a confidence floor the reader may set.
    let mut scored: Vec<f64> = rows
        .iter()
        .filter(|r| parse_score(&r.text).0.is_some())
        .map(|r| r.confidence)
        .collect();
    scored.sort_unstable_by(f64::total_cmp);
    let middle = scored.len() / 2;
    let median_conf = if scored.is_empty() {
        None
    } else if scored.len().is_multiple_of(2) {
        middle
            .checked_sub(1)
            .and_then(|i| scored.get(i))
            .zip(scored.get(middle))
            .map(|(a, b)| (a + b) / 2.0)
    } else {
        scored.get(middle).copied()
    };
    println!(
        "confidence of texts read as a score: count={} min={} median={}",
        scored.len(),
        scored
            .first()
            .map_or("-".to_string(), |c| format!("{c:.5}")),
        median_conf.map_or("-".to_string(), |c| format!("{c:.5}"))
    );
    match peak_memory() {
        Some(peak) => println!("peak memory: {peak}"),
        None => println!(
            "peak memory: not readable in-process on this OS; measure from outside \
             (Windows: PeakWorkingSet64 of the process)"
        ),
    }

    for row in rows.iter().filter(|r| r.text != r.sample.py_text) {
        println!(
            "differs: {} ({}) python={:?} rust={:?} conf={:.5}",
            row.sample.file, row.sample.case, row.sample.py_text, row.text, row.confidence
        );
    }
}

/// Linux and Android report the peak resident size in /proc; elsewhere it needs an OS call.
fn peak_memory() -> Option<String> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    status
        .lines()
        .find(|line| line.starts_with("VmHWM:"))
        .map(|line| line.trim_start_matches("VmHWM:").trim().to_string())
}
