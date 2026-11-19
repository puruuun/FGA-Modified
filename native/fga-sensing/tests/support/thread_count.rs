//! Thread-count arithmetic for ort_threads.rs, free of any OS or runtime so that it is tested
//! everywhere (ort_thread_helpers.rs).

#![allow(dead_code)]

use std::time::Duration;

/// Signed, so a count that went down says so instead of overflowing.
pub fn difference(before: usize, after: usize) -> i64 {
    after as i64 - before as i64
}

/// Reads `count` until it is back at `baseline` or below, at most `attempts` times with
/// `pause` between, and returns that count.
pub fn settle(
    mut count: impl FnMut() -> usize,
    baseline: usize,
    attempts: usize,
    pause: Duration,
) -> Result<usize, String> {
    let mut seen = Vec::new();
    for attempt in 0..attempts {
        let now = count();
        if now <= baseline {
            return Ok(now);
        }
        seen.push(now);
        if attempt + 1 < attempts {
            std::thread::sleep(pause);
        }
    }
    Err(format!(
        "the thread count never came back to the baseline {baseline}: read {seen:?}"
    ))
}
