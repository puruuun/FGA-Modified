//! `--table` (issue #639): the support-score reader itself, through `SupportScoreReader`, on every
//! local sample set at each combination of model width and row trim, as one table. Nothing of the
//! reader is reimplemented here, so the table is what the product path gives.
//!
//! width_decode_eval --table <model.onnx> <ocr dir> <out dir> [--widths 256,320] [--trims 0:0,3:4]
//!
//! `<ocr dir>` holds `samples` (index.json), `scan-samples/{all-full,tablet-full}` and
//! `native-samples/{tablet-jp,tablet-kr}/crop720` (labels.tsv, column `text` is the truth) and
//! `scan-samples/{all-partial,tablet-partial}` (list.tsv, crops the screen edge cut). One TSV per
//! set and setting goes to `<out dir>`, so two settings can be diffed file by file.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use fga_sensing::Recognizer;
use fga_sensing::backend::{TractBackend, TractSettings};
use fga_sensing::image::GrayImage;
use fga_sensing::readers::support_score::{
    DEFAULT_MIN_CHAR_CONFIDENCE, DEFAULT_MIN_CONFIDENCE, Format, RowTrim, ScoreConfig,
    SupportScore, SupportScoreReader, parse,
};

use super::{b_sample, exact_expect, read_tsv};
use crate::samples::{self, Sample};
use crate::wrong::{self, Expect, classify};

struct Table {
    model: PathBuf,
    root: PathBuf,
    out: PathBuf,
    widths: Vec<usize>,
    trims: Vec<RowTrim>,
}

enum Kind {
    Index,
    Labels,
    Partial,
}

struct Set {
    name: &'static str,
    kind: Kind,
    /// Where the crops are, relative to the ocr dir.
    dir: &'static str,
}

const SETS: [Set; 7] = [
    Set {
        name: "samples",
        kind: Kind::Index,
        dir: "samples",
    },
    Set {
        name: "all-full",
        kind: Kind::Labels,
        dir: "scan-samples/all-full",
    },
    Set {
        name: "tablet-full",
        kind: Kind::Labels,
        dir: "scan-samples/tablet-full",
    },
    Set {
        name: "tablet-jp-crop720",
        kind: Kind::Labels,
        dir: "native-samples/tablet-jp",
    },
    Set {
        name: "tablet-kr-crop720",
        kind: Kind::Labels,
        dir: "native-samples/tablet-kr",
    },
    Set {
        name: "all-partial",
        kind: Kind::Partial,
        dir: "scan-samples/all-partial",
    },
    Set {
        name: "tablet-partial",
        kind: Kind::Partial,
        dir: "scan-samples/tablet-partial",
    },
];

struct Item {
    file: String,
    sample: Sample,
    expect: Expect,
    crop: GrayImage,
}

fn usage() -> String {
    "usage: width_decode_eval --table <model.onnx> <ocr dir> <out dir> \
     [--widths 256,320] [--trims 0:0,3:4]"
        .to_owned()
}

pub fn run(mut args: impl Iterator<Item = String>) -> Result<(), String> {
    let mut next = || args.next().ok_or_else(usage);
    let mut table = Table {
        model: PathBuf::from(next()?),
        root: PathBuf::from(next()?),
        out: PathBuf::from(next()?),
        widths: vec![256, 320],
        trims: vec![RowTrim::NONE, RowTrim::MEASURED],
    };
    while let Ok(flag) = next() {
        match flag.as_str() {
            "--widths" => {
                table.widths = next()?
                    .split(',')
                    .map(|w| w.trim().parse::<usize>().map_err(|e| format!("{w}: {e}")))
                    .collect::<Result<_, _>>()?;
            }
            "--trims" => {
                table.trims = next()?
                    .split(',')
                    .map(|t| {
                        let (top, bottom) = t.split_once(':').ok_or_else(usage)?;
                        let part = |v: &str| v.parse::<usize>().map_err(|e| format!("{t}: {e}"));
                        Ok(RowTrim {
                            top: part(top)?,
                            bottom: part(bottom)?,
                        })
                    })
                    .collect::<Result<_, String>>()?;
            }
            _ => return Err(usage()),
        }
    }
    std::fs::create_dir_all(&table.out).map_err(|e| e.to_string())?;
    let sets: Vec<(&Set, Vec<Item>)> = SETS
        .iter()
        .map(|set| load(&table.root, set).map(|items| (set, items)))
        .collect::<Result<_, _>>()?;
    for &width in &table.widths {
        for &trim in &table.trims {
            println!(
                "\n== width {width}, trim top {} bottom {}",
                trim.top, trim.bottom
            );
            for (set, items) in &sets {
                let scores = read_all(&table.model, width, trim, items)?;
                let summary = summarise(set, items, &scores);
                println!("{:18} {}", set.name, summary.line());
                for extra in summary.extras {
                    println!("{:18}   {extra}", "");
                }
                let name = format!("{}-w{width}-t{}b{}.tsv", set.name, trim.top, trim.bottom);
                write_tsv(&table.out.join(name), items, &scores)?;
            }
        }
    }
    Ok(())
}

fn load(root: &Path, set: &Set) -> Result<Vec<Item>, String> {
    let dir = root.join(set.dir);
    match set.kind {
        Kind::Index => samples::load_index(&dir)?
            .into_iter()
            .map(|sample| {
                let crop = samples::load_gray(&dir.join(&sample.file))?;
                let expect = wrong::expect(&sample, None);
                Ok(Item {
                    file: sample.file.clone(),
                    sample,
                    expect,
                    crop,
                })
            })
            .collect(),
        Kind::Labels => {
            // The native sets keep the device's own 60x30 crop in a folder of their own.
            let crops = if dir.join("crop720").is_dir() {
                dir.join("crop720")
            } else {
                dir.clone()
            };
            read_tsv(&dir.join("labels.tsv"))?
                .into_iter()
                .map(|row| {
                    let (Some(file), Some(text)) = (row.first(), row.get(1)) else {
                        return Err("labels.tsv: short row".to_owned());
                    };
                    let parsed = parse(text, Format::Strict)
                        .ok_or_else(|| format!("{file}: label {text:?}"))?;
                    Ok(Item {
                        file: file.clone(),
                        sample: b_sample(file, parsed.class, parsed.grand),
                        expect: exact_expect(parsed.class, parsed.grand),
                        crop: samples::load_gray(&crops.join(file))?,
                    })
                })
                .collect()
        }
        Kind::Partial => read_tsv(&dir.join("list.tsv"))?
            .into_iter()
            .map(|row| {
                let file = row.first().ok_or("list.tsv: short row")?.clone();
                Ok(Item {
                    sample: b_sample(&file, 0, None),
                    expect: exact_expect(0, None),
                    crop: samples::load_gray(&dir.join(&file))?,
                    file,
                })
            })
            .collect(),
    }
}

/// Reads on several threads, each with a backend of its own, in the order of `items`.
fn read_all(
    model: &Path,
    width: usize,
    trim: RowTrim,
    items: &[Item],
) -> Result<Vec<SupportScore>, String> {
    let workers = std::thread::available_parallelism()
        .map_or(2, |n| n.get())
        .clamp(1, 8);
    let parts: Vec<Result<Vec<(usize, SupportScore)>, String>> = std::thread::scope(|scope| {
        let handles: Vec<_> = (0..workers)
            .map(|worker| {
                scope.spawn(move || -> Result<Vec<(usize, SupportScore)>, String> {
                    let settings = TractSettings {
                        min_width: width,
                        threads: 1,
                    };
                    let backend =
                        TractBackend::load_file_with(model, settings).map_err(|e| e.to_string())?;
                    let recognizer = Recognizer::new(backend).map_err(|e| e.to_string())?;
                    let config = ScoreConfig {
                        row_trim: trim,
                        ..ScoreConfig::default()
                    };
                    let mut reader =
                        SupportScoreReader::new(recognizer, config).map_err(|e| e.to_string())?;
                    let mut out = Vec::new();
                    for i in (worker..items.len()).step_by(workers) {
                        if let Some(item) = items.get(i) {
                            out.push((i, reader.read(&item.crop).map_err(|e| e.to_string())?));
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
    Ok(all.into_iter().map(|(_, s)| s).collect())
}

#[derive(Default)]
struct Summary {
    n: usize,
    /// A value was read and no field of it is wrong (a read with no known truth counts here).
    correct: usize,
    wrong: usize,
    refused: BTreeMap<&'static str, usize>,
    pos62: usize,
    read62: usize,
    pos16: usize,
    read16: usize,
    f62: usize,
    f16: usize,
    lowest_conf: Option<f64>,
    lowest_char: Option<f32>,
    below_090: usize,
    /// Correct reads within 0.02 of a floor: the text confidence or the weakest character.
    borderline: usize,
    extras: Vec<String>,
}

impl Summary {
    fn line(&self) -> String {
        let refused: Vec<String> = self
            .refused
            .iter()
            .map(|(r, n)| format!("{r} {n}"))
            .collect();
        format!(
            "n {} correct {} refused {} [{}] WRONG {} | +62 {}/{} +16 {}/{} F62 {} F16 {} | \
             conf min {} char min {} below0.90 {} borderline {}",
            self.n,
            self.correct,
            self.refused.values().sum::<usize>(),
            refused.join(", "),
            self.wrong,
            self.read62,
            self.pos62,
            self.read16,
            self.pos16,
            self.f62,
            self.f16,
            self.lowest_conf.map_or("-".into(), |c| format!("{c:.5}")),
            self.lowest_char.map_or("-".into(), |c| format!("{c:.5}")),
            self.below_090,
            self.borderline,
        )
    }
}

fn summarise(set: &Set, items: &[Item], scores: &[SupportScore]) -> Summary {
    let mut s = Summary {
        n: items.len(),
        ..Summary::default()
    };
    let partial = matches!(set.kind, Kind::Partial);
    for (item, score) in items.iter().zip(scores) {
        let (class, grand) = (score.class.value(), score.grand.value());
        let obs = &score.observation;
        if class.is_none() && grand.is_none() {
            let reason = score.class.reason().map_or("?", |r| r.name());
            *s.refused.entry(reason).or_default() += 1;
        }
        if partial {
            if class.is_some() || grand.is_some() {
                s.extras.push(format!(
                    "READ {}: {:?} class {class:?} grand {grand:?} conf {:.5}",
                    item.file, obs.text, obs.confidence
                ));
            }
            continue;
        }
        let sample = &item.sample;
        let p62 = sample.is62 == Some(true) || sample.cls == Some(62);
        let p16 = sample.is16 == Some(true) || (sample.grand == Some(16) && !item.expect.no_grand);
        s.pos62 += usize::from(p62);
        s.pos16 += usize::from(p16);
        s.read62 += usize::from(p62 && class == Some(62));
        s.read16 += usize::from(p16 && grand == Some(16));
        let k = classify(
            sample,
            item.expect.truth,
            item.expect.no_grand,
            class,
            grand,
        );
        s.f62 += k.f62;
        s.f16 += k.f16;
        if class.is_none() && grand.is_none() {
            continue;
        }
        if k.wrong() > 0 {
            s.wrong += 1;
            s.extras.push(format!(
                "WRONG {}: {:?} class {class:?} grand {grand:?}",
                item.file, obs.text
            ));
            continue;
        }
        s.correct += 1;
        s.lowest_conf = Some(
            s.lowest_conf
                .map_or(obs.confidence, |c| c.min(obs.confidence)),
        );
        let weakest = obs.min_char_confidence.unwrap_or(0.0);
        s.lowest_char = Some(s.lowest_char.map_or(weakest, |c| c.min(weakest)));
        s.below_090 += usize::from(obs.confidence < 0.90);
        s.borderline += usize::from(
            obs.confidence - DEFAULT_MIN_CONFIDENCE < 0.02
                || weakest - DEFAULT_MIN_CHAR_CONFIDENCE < 0.02,
        );
    }
    s
}

fn write_tsv(path: &Path, items: &[Item], scores: &[SupportScore]) -> Result<(), String> {
    let mut text = String::from("file\ttext\tconfidence\tmin_char\tclass\tgrand\treason\n");
    for (item, score) in items.iter().zip(scores) {
        let o = &score.observation;
        let number = |v: Option<u8>| v.map_or(String::new(), |v| v.to_string());
        let _ = writeln!(
            text,
            "{}\t{}\t{:.5}\t{}\t{}\t{}\t{}",
            item.file,
            o.text,
            o.confidence,
            o.min_char_confidence
                .map_or(String::new(), |c| format!("{c:.5}")),
            number(score.class.value()),
            number(score.grand.value()),
            score.class.reason().map_or("", |r| r.name()),
        );
    }
    std::fs::write(path, text).map_err(|e| format!("{}: {e}", path.display()))
}
