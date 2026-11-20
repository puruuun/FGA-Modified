//! `isLoaded` never waits for the engine lock: while another call holds it (a load or a read), it
//! answers busy at once. The lock is process-wide, so this file is its own test binary.

#[cfg(test)]
mod busy {
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    use fga_ffi::score_engine::{
        CODE_BUSY, CODE_NO, is_loaded_entry, with_engine_locked_for_tests,
    };

    #[test]
    fn is_loaded_answers_busy_while_the_lock_is_held() {
        let answer = with_engine_locked_for_tests(|| {
            let (sender, receiver) = mpsc::channel();
            thread::spawn(move || sender.send(is_loaded_entry()));
            receiver.recv_timeout(Duration::from_secs(5))
        })
        .unwrap();
        assert_eq!(answer, Ok(CODE_BUSY));
        assert_eq!(is_loaded_entry(), CODE_NO);
    }
}
