//! Runs the real PP-OCRv6 model on local samples. Needs `FGA_OCR_MODEL` (the .onnx file) and
//! `FGA_OCR_SAMPLES` (the folder with index.json); without them each test says so and returns,
//! because neither file may be committed (docs/agents/rust-native.md, "Test data").

#![cfg(any(feature = "tract", feature = "ort"))]

#[path = "support/samples.rs"]
mod samples;

// Clippy allows unwrap only in test code; these helpers run only from the tests below.
#[cfg(test)]
mod checks {
    use super::samples;
    use std::path::PathBuf;

    use fga_sensing::Recognizer;
    use fga_sensing::backend::Backend;
    use fga_sensing::image::GrayImage;

    /// 25 samples spread over the whole set: every 15th file.
    const FIXED: [usize; 25] = [
        0, 15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240, 255, 270, 285,
        300, 315, 330, 345, 360,
    ];

    const SCORE_CHARACTERS: &str = "+/0123456789";

    /// A sample file, its true class and Grand score, and the shifts to read it at.
    type Work<'a> = (&'a str, u8, Option<u8>, Vec<(isize, isize)>);

    /// Both variables set: run. Neither: skip, as on CI. Only one: a broken local setup that
    /// would otherwise pass silently, so fail.
    fn local_files(test: &str) -> Option<(PathBuf, PathBuf)> {
        match (
            std::env::var_os("FGA_OCR_MODEL"),
            std::env::var_os("FGA_OCR_SAMPLES"),
        ) {
            (Some(model), Some(samples)) => Some((model.into(), samples.into())),
            (None, None) => {
                println!("{test}: skipped, FGA_OCR_MODEL and FGA_OCR_SAMPLES are not set");
                None
            }
            (model, samples) => panic!(
                "{test}: only one of FGA_OCR_MODEL ({}) and FGA_OCR_SAMPLES ({}) is set; set both \
                 to run the model tests or neither to skip them",
                if model.is_some() { "set" } else { "missing" },
                if samples.is_some() { "set" } else { "missing" },
            ),
        }
    }

    /// `load` gives a backend at the width the Python results used, RapidOCR's 320: the
    /// confidences are compared too, and they depend on the padded width.
    pub fn fixed_samples_match_python<B: Backend>(
        test: &str,
        load: impl FnOnce(&std::path::Path) -> B,
    ) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let index = samples::load_index(&dir).unwrap();
        let mut recognizer = Recognizer::new(load(&model)).unwrap();
        assert_eq!(recognizer.min_width(), 320);
        assert_eq!(recognizer.characters().class_count(), 18710);
        let mut checked = 0;
        let mut with_text = 0;
        for &i in &FIXED {
            let sample = &index[i];
            let crop = samples::load_gray(&dir.join(&sample.file)).unwrap();
            let decoded = recognizer.recognize(&crop, None).unwrap();
            assert_eq!(
                decoded.text, sample.py_text,
                "{} ({})",
                sample.file, sample.case
            );
            assert!(
                (decoded.confidence - sample.py_conf).abs() < 1e-4,
                "{}: confidence {} against Python {}",
                sample.file,
                decoded.confidence,
                sample.py_conf
            );
            checked += 1;
            with_text += usize::from(!sample.py_text.is_empty());
        }
        // Blank crops read as "" on both sides; most of the fixed set must hold real text, or the
        // test would pass on an engine that reads nothing.
        assert!(
            with_text * 2 > checked,
            "only {with_text} of {checked} samples have text"
        );
        println!(
            "{test}: {checked} samples equal to the Python text, {with_text} of them non-empty"
        );
    }

    /// Issue #549: the same fixed samples through a recogniser built by `build`. With
    /// `same_confidence` false only the texts are compared: a narrower input changes the
    /// confidences, and on these 25 samples the PC evaluation found the texts unchanged at 160.
    #[cfg(feature = "tract")]
    pub fn fixed_samples_with<B: Backend>(
        test: &str,
        build: impl FnOnce(&std::path::Path) -> Recognizer<B>,
        same_confidence: bool,
    ) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let index = samples::load_index(&dir).unwrap();
        let mut recognizer = build(&model);
        for &i in &FIXED {
            let sample = &index[i];
            let crop = samples::load_gray(&dir.join(&sample.file)).unwrap();
            let decoded = recognizer.recognize(&crop, None).unwrap();
            assert_eq!(
                decoded.text, sample.py_text,
                "{} ({})",
                sample.file, sample.case
            );
            if same_confidence {
                assert!(
                    (decoded.confidence - sample.py_conf).abs() < 1e-4,
                    "{}: confidence {} against Python {}",
                    sample.file,
                    decoded.confidence,
                    sample.py_conf
                );
            }
        }
        println!("{test}: {} samples equal to the Python text", FIXED.len());
    }

    /// `load` as for [`fixed_samples_match_python`]: the free reads are compared with Python's.
    pub fn allowed_set_keeps_only_score_characters<B: Backend>(
        test: &str,
        load: impl FnOnce(&std::path::Path) -> B,
    ) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let index = samples::load_index(&dir).unwrap();
        let mut recognizer = Recognizer::new(load(&model)).unwrap();
        let allowed = recognizer.allowed_set(SCORE_CHARACTERS).unwrap();
        // Python read "_", "X", "X" and "A" on these blank-looking crops.
        for file in ["0002.png", "0005.png", "0052.png", "0068.png"] {
            let sample = index.iter().find(|s| s.file == file).unwrap();
            let crop = samples::load_gray(&dir.join(file)).unwrap();
            let free = recognizer.recognize(&crop, None).unwrap();
            assert_eq!(free.text, sample.py_text, "{file}");
            let limited = recognizer.recognize(&crop, Some(&allowed)).unwrap();
            assert!(
                limited.text.chars().all(|c| SCORE_CHARACTERS.contains(c)),
                "{file}: {:?}",
                limited.text
            );
        }
    }

    /// The support-score reader (issue #555) on seven fixed crops, one per case the rules exist
    /// for. The reasons are the reader's own, so a rule that stops firing fails here with the
    /// file name.
    pub fn support_score_reader_decides_fixed_samples<B: Backend>(test: &str) {
        use fga_sensing::readers::support_score::{
            Display, Reason, Score, ScoreConfig, SupportScoreReader,
        };
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let mut reader = SupportScoreReader::new(
            Recognizer::new(B::load_file(&model).unwrap()).unwrap(),
            ScoreConfig::default(),
        )
        .unwrap();
        let cases: [(&str, Score, Score, Display); 7] = [
            // A plain +62.
            (
                "0044.png",
                Score::Value(62),
                Score::Unknown(Reason::NotShown),
                Display::Ordinary,
            ),
            // A compound +62/+16.
            (
                "0183.png",
                Score::Value(62),
                Score::Value(16),
                Display::Compound,
            ),
            (
                "0000.png",
                Score::Value(62),
                Score::Value(16),
                Display::Compound,
            ),
            // A class value that is not 62.
            (
                "0269.png",
                Score::Value(45),
                Score::Unknown(Reason::NotShown),
                Display::Ordinary,
            ),
            // A crop with no score in it.
            (
                "0057.png",
                Score::Unknown(Reason::Blank),
                Score::Unknown(Reason::Blank),
                Display::Unknown,
            ),
            // "+62/+16" with the lower part of the digits cut off: valid text, labelled not to
            // be accepted.
            (
                "0070.png",
                Score::Unknown(Reason::Clipped),
                Score::Unknown(Reason::Clipped),
                Display::Unknown,
            ),
            // The leading plus not read: "62/+16".
            (
                "0168.png",
                Score::Unknown(Reason::Format),
                Score::Unknown(Reason::Format),
                Display::Unknown,
            ),
        ];
        for (file, class, grand, display) in cases {
            let crop = samples::load_gray(&dir.join(file)).unwrap();
            let score = reader.read(&crop).unwrap();
            let seen = format!("{file}: text {:?}", score.observation.text);
            assert_eq!(score.class, class, "{seen}");
            assert_eq!(score.grand, grand, "{seen}");
            assert_eq!(score.display, display, "{seen}");
        }
        println!("{test}: {} samples decided as expected", cases.len());
    }

    /// The cut sweep of `support_score_eval` on a fixed handful of samples: every shift (right and
    /// left by 1 to 20 columns, down and up by 1 to 10 rows) of a crop whose value is known, read
    /// and split by what the automation does with a wrong number. SYNTHETIC cuts, see
    /// `samples::shifted`; the full sweep is the evaluation tool's.
    ///
    /// The automation acts only on class 62 and a compound's Grand 16, so a 62 or a 16 that is not
    /// the true value (F62, F16) is a wrong pick and must not occur at all. Any other wrong number
    /// acts as a miss; `measured_other` is what this reader returned on that backend when it was
    /// measured, a bound that shows a regression, not a target.
    pub fn cut_sweep_has_no_wrong_pick<B: Backend>(test: &str, measured_other: usize) {
        use fga_sensing::readers::support_score::{ScoreConfig, SupportScoreReader};
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let mut reader = SupportScoreReader::new(
            Recognizer::new(B::load_file(&model).unwrap()).unwrap(),
            ScoreConfig::default(),
        )
        .unwrap();
        // (file, class score, Grand score) as the labels give them; None where the field has none.
        // Every sample here has its exact truth, so a value is wrong exactly when it differs from
        // it, and F62, F16 and OTHER below partition the wrong values.
        // The last three are compounds whose Grand score is not 16: a cut that leaves "+10/+1"
        // must not be taken for a 16.
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
        // Shifted far to the left, these leave only "+16", which reads as an ordinary score of
        // 16: a number that is not 62 and so a miss for the automation, but one the cut sweep
        // should keep seeing.
        let far_left: [(&str, u8); 4] = [
            ("0014.png", 0),
            ("0021.png", 6),
            ("0034.png", 1),
            ("0084.png", 2),
        ];
        let mut work: Vec<Work> = known
            .iter()
            .map(|&(f, c, g)| (f, c, g, shifts.clone()))
            .collect();
        for (file, class) in far_left {
            work.push((file, class, Some(16), (17..=20).map(|d| (-d, 0)).collect()));
        }
        // Per direction: F62, F16, OTHER, correct 62 or 16.
        let mut split = std::collections::BTreeMap::<&str, [usize; 4]>::new();
        let mut wrong_picks = Vec::new();
        for (file, class, grand, list) in &work {
            let crop = samples::load_gray(&dir.join(file)).unwrap();
            for &(dx, dy) in list {
                let moved = samples::shifted(&crop, dx, dy).unwrap();
                let score = reader.read(&moved).unwrap();
                let at = format!(
                    "{file} shifted ({dx}, {dy}): text {:?}",
                    score.observation.text
                );
                let direction = match (dx.signum(), dy.signum()) {
                    (1, _) => "right",
                    (-1, _) => "left",
                    (_, 1) => "down",
                    _ => "up",
                };
                let counts = split.entry(direction).or_default();
                if let Some(value) = score.class.value() {
                    if value == *class {
                        counts[3] += usize::from(value == 62);
                    } else if value == 62 {
                        counts[0] += 1;
                        wrong_picks.push(format!("F62: {at}"));
                    } else {
                        counts[2] += 1;
                    }
                }
                if let Some(value) = score.grand.value() {
                    if Some(value) == *grand {
                        counts[3] += usize::from(value == 16);
                    } else if value == 16 {
                        counts[1] += 1;
                        wrong_picks.push(format!("F16: {at}"));
                    } else {
                        counts[2] += 1;
                    }
                }
            }
        }
        assert!(wrong_picks.is_empty(), "wrong picks: {wrong_picks:?}");
        let other: usize = split.values().map(|c| c[2]).sum();
        let correct: usize = split.values().map(|c| c[3]).sum();
        println!(
            "{test}: per direction [F62, F16, OTHER, correct 62/16]: {split:?}; OTHER {other} (bound {measured_other})"
        );
        // Zero wrong picks also holds for a reader that never answers, so some 62 or 16 must
        // have been accepted on a cut crop: the smallest shifts leave the field whole.
        assert!(correct > 0, "no shifted crop returned a 62 or a 16");
        assert!(
            other <= measured_other,
            "{other} other wrong numbers, measured {measured_other}: {split:?}"
        );
    }

    pub fn loads_from_bytes_and_reads_wide_crops<B: Backend>(test: &str) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let bytes = std::fs::read(&model).unwrap();
        let mut recognizer = Recognizer::new(B::load_bytes(&bytes).unwrap()).unwrap();
        let index = samples::load_index(&dir).unwrap();
        let sample = &index[0];
        let crop = samples::load_gray(&dir.join(&sample.file)).unwrap();
        assert_eq!(
            recognizer.recognize(&crop, None).unwrap().text,
            sample.py_text
        );

        // Three crops side by side: 180x30 becomes 288 columns, wider than tract's 256 plan, so
        // tract plans that width on first use.
        let wide: Vec<u8> = crop
            .pixels()
            .chunks(crop.width())
            .flat_map(|row| row.iter().chain(row).chain(row).copied())
            .collect();
        let wide = GrayImage::new(crop.width() * 3, crop.height(), wide).unwrap();
        let text = recognizer.recognize(&wide, None).unwrap().text;
        assert!(text.contains(&sample.py_text), "{text:?}");
    }
}

#[cfg(feature = "tract")]
mod tract {
    use std::path::Path;

    use fga_sensing::Recognizer;
    use fga_sensing::backend::{Backend, READER_MIN_WIDTH, TractBackend, TractSettings};
    use fga_sensing::preprocess::MIN_MODEL_WIDTH;

    /// The width the Python results used, not the reader's.
    #[cfg(test)]
    fn at_python_width(model: &Path) -> TractBackend {
        let settings = TractSettings {
            min_width: MIN_MODEL_WIDTH,
            threads: 1,
        };
        TractBackend::load_file_with(model, settings).unwrap()
    }

    #[test]
    fn fixed_samples_match_python() {
        super::checks::fixed_samples_match_python("tract fixed samples", at_python_width);
    }

    #[test]
    fn allowed_set_keeps_only_score_characters() {
        super::checks::allowed_set_keeps_only_score_characters(
            "tract allowed set",
            at_python_width,
        );
    }

    /// Issue #591: a default load plans the reader width, and a recogniser on it reads a score
    /// crop without planning another.
    #[test]
    fn the_default_load_reads_at_the_reader_width() {
        let Some(model) = std::env::var_os("FGA_OCR_MODEL") else {
            println!("tract reader width: skipped, FGA_OCR_MODEL is not set");
            return;
        };
        let backend = TractBackend::load_file(Path::new(&model)).unwrap();
        assert_eq!(backend.planned_widths(), vec![READER_MIN_WIDTH]);
        let mut recognizer = Recognizer::new(backend).unwrap();
        assert_eq!(recognizer.min_width(), READER_MIN_WIDTH);
        let crop = fga_sensing::image::GrayImage::new(60, 30, vec![128; 60 * 30]).unwrap();
        recognizer.recognize(&crop, None).unwrap();
        assert_eq!(
            recognizer.backend().planned_widths(),
            vec![READER_MIN_WIDTH]
        );
    }

    #[test]
    fn support_score_reader_decides_fixed_samples() {
        super::checks::support_score_reader_decides_fixed_samples::<TractBackend>(
            "tract support score",
        );
    }

    #[test]
    fn cut_sweep_has_no_wrong_pick() {
        super::checks::cut_sweep_has_no_wrong_pick::<TractBackend>("tract cut sweep", 46);
    }

    #[test]
    fn loads_from_bytes_and_reads_wide_crops() {
        super::checks::loads_from_bytes_and_reads_wide_crops::<TractBackend>(
            "tract bytes and wide",
        );
    }

    #[test]
    fn a_narrower_input_keeps_the_fixed_texts() {
        let settings = TractSettings {
            min_width: 160,
            threads: 1,
        };
        super::checks::fixed_samples_with(
            "tract width 160",
            |model| {
                let backend = TractBackend::load_file_with(model, settings).unwrap();
                assert_eq!(backend.planned_widths(), vec![160]);
                Recognizer::new(backend)
                    .unwrap()
                    .with_min_width(160)
                    .unwrap()
            },
            false,
        );
    }

    #[cfg(feature = "tract-threads")]
    #[test]
    fn two_threads_read_the_fixed_samples_like_python() {
        let settings = TractSettings {
            min_width: 320,
            threads: 2,
        };
        super::checks::fixed_samples_with(
            "tract two threads",
            |model| {
                let backend = TractBackend::load_file_with(model, settings).unwrap();
                assert_eq!(backend.threads(), 2);
                Recognizer::new(backend).unwrap()
            },
            true,
        );
    }
}

#[cfg(feature = "ort")]
mod ort {
    use fga_sensing::backend::{Backend, OrtBackend};

    // ONNX Runtime plans no width, so it pads to RapidOCR's 320, the Python results' width.
    #[cfg(test)]
    fn load(model: &std::path::Path) -> OrtBackend {
        OrtBackend::load_file(model).unwrap()
    }

    #[test]
    fn fixed_samples_match_python() {
        super::checks::fixed_samples_match_python("ort fixed samples", load);
    }

    #[test]
    fn allowed_set_keeps_only_score_characters() {
        super::checks::allowed_set_keeps_only_score_characters("ort allowed set", load);
    }

    #[test]
    fn support_score_reader_decides_fixed_samples() {
        super::checks::support_score_reader_decides_fixed_samples::<OrtBackend>(
            "ort support score",
        );
    }

    #[test]
    fn cut_sweep_has_no_wrong_pick() {
        super::checks::cut_sweep_has_no_wrong_pick::<OrtBackend>("ort cut sweep", 47);
    }

    #[test]
    fn loads_from_bytes_and_reads_wide_crops() {
        super::checks::loads_from_bytes_and_reads_wide_crops::<OrtBackend>("ort bytes and wide");
    }
}
