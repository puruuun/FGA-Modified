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

    fn local_files(test: &str) -> Option<(PathBuf, PathBuf)> {
        match (
            std::env::var_os("FGA_OCR_MODEL"),
            std::env::var_os("FGA_OCR_SAMPLES"),
        ) {
            (Some(model), Some(samples)) => Some((model.into(), samples.into())),
            _ => {
                println!("{test}: skipped, FGA_OCR_MODEL and FGA_OCR_SAMPLES are not both set");
                None
            }
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
        }
        println!("{test}: {checked} samples equal to the Python text");
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
    use fga_sensing::backend::TractBackend;

    #[test]
    fn fixed_samples_match_python() {
        super::checks::fixed_samples_match_python::<TractBackend>("tract fixed samples");
    }

    #[test]
    fn allowed_set_keeps_only_score_characters() {
        super::checks::allowed_set_keeps_only_score_characters::<TractBackend>("tract allowed set");
    }

    #[test]
    fn loads_from_bytes_and_reads_wide_crops() {
        super::checks::loads_from_bytes_and_reads_wide_crops::<TractBackend>(
            "tract bytes and wide",
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
    fn loads_from_bytes_and_reads_wide_crops() {
        super::checks::loads_from_bytes_and_reads_wide_crops::<OrtBackend>("ort bytes and wide");
    }
}
