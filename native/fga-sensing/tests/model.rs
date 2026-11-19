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

    pub fn fixed_samples_match_python<B: Backend>(test: &str) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let index = samples::load_index(&dir).unwrap();
        let mut recognizer = Recognizer::new(B::load_file(&model).unwrap());
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

    pub fn allowed_set_keeps_only_score_characters<B: Backend>(test: &str) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let index = samples::load_index(&dir).unwrap();
        let mut recognizer = Recognizer::new(B::load_file(&model).unwrap());
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

    /// The support-score reader (issue #555) on six fixed crops, one per case the rules exist
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
            Recognizer::new(B::load_file(&model).unwrap()),
            ScoreConfig::default(),
        )
        .unwrap();
        let cases: [(&str, Score, Score, Display); 6] = [
            // A plain +62.
            (
                "0044.png",
                Score::Value(62),
                Score::Unknown(Reason::NotShown),
                Display::Ordinary,
            ),
            // A compound +62/+16.
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
            // The slash read as a 1: "+571+16".
            (
                "0299.png",
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

    pub fn loads_from_bytes_and_reads_wide_crops<B: Backend>(test: &str) {
        let Some((model, dir)) = local_files(test) else {
            return;
        };
        let bytes = std::fs::read(&model).unwrap();
        let mut recognizer = Recognizer::new(B::load_bytes(&bytes).unwrap());
        let index = samples::load_index(&dir).unwrap();
        let sample = &index[0];
        let crop = samples::load_gray(&dir.join(&sample.file)).unwrap();
        assert_eq!(
            recognizer.recognize(&crop, None).unwrap().text,
            sample.py_text
        );

        // Three crops side by side: 180x30, past the 320/48 ratio, so the input is wider than 320.
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
    use fga_sensing::Recognizer;
    use fga_sensing::backend::{TractBackend, TractSettings};

    #[test]
    fn fixed_samples_match_python() {
        super::checks::fixed_samples_match_python::<TractBackend>("tract fixed samples");
    }

    #[test]
    fn allowed_set_keeps_only_score_characters() {
        super::checks::allowed_set_keeps_only_score_characters::<TractBackend>("tract allowed set");
    }

    #[test]
    fn support_score_reader_decides_fixed_samples() {
        super::checks::support_score_reader_decides_fixed_samples::<TractBackend>(
            "tract support score",
        );
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
                Recognizer::new(backend).with_min_width(160).unwrap()
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
                Recognizer::new(backend)
            },
            true,
        );
    }
}

#[cfg(feature = "ort")]
mod ort {
    use fga_sensing::backend::OrtBackend;

    #[test]
    fn fixed_samples_match_python() {
        super::checks::fixed_samples_match_python::<OrtBackend>("ort fixed samples");
    }

    #[test]
    fn allowed_set_keeps_only_score_characters() {
        super::checks::allowed_set_keeps_only_score_characters::<OrtBackend>("ort allowed set");
    }

    #[test]
    fn support_score_reader_decides_fixed_samples() {
        super::checks::support_score_reader_decides_fixed_samples::<OrtBackend>(
            "ort support score",
        );
    }

    #[test]
    fn loads_from_bytes_and_reads_wide_crops() {
        super::checks::loads_from_bytes_and_reads_wide_crops::<OrtBackend>("ort bytes and wide");
    }
}
