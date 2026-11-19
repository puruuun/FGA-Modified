//! The counting helpers of ort_threads.rs, with fake counters. A separate test binary, because
//! ort_threads.rs must run alone in its process while it counts threads.

#[cfg(test)]
#[path = "support/thread_count.rs"]
mod thread_count;

#[cfg(test)]
mod helpers {
    use std::time::Duration;

    use super::thread_count::{difference, settle};

    #[test]
    fn difference_is_signed() {
        assert_eq!(difference(10, 12), 2);
        assert_eq!(difference(12, 10), -2);
        assert_eq!(difference(0, 0), 0);
    }

    #[test]
    fn settle_waits_for_stale_threads_to_go() {
        let mut readings = [9, 9, 7].into_iter();
        let mut calls = 0;
        let settled = settle(
            || {
                calls += 1;
                readings.next().unwrap()
            },
            7,
            5,
            Duration::ZERO,
        );
        assert_eq!(settled, Ok(7));
        assert_eq!(calls, 3);
    }

    #[test]
    fn settle_accepts_a_count_below_the_baseline() {
        assert_eq!(settle(|| 6, 7, 1, Duration::ZERO), Ok(6));
    }

    #[test]
    fn settle_gives_up_with_the_counts() {
        let mut calls = 0;
        let settled = settle(
            || {
                calls += 1;
                9
            },
            7,
            4,
            Duration::ZERO,
        );
        assert_eq!(
            settled,
            Err("the thread count never came back to the baseline 7: read [9, 9, 9, 9]".to_owned())
        );
        assert_eq!(calls, 4);
    }
}
