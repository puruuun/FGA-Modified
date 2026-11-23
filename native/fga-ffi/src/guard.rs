use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, MutexGuard, PoisonError};

/// Runs `body` and renders its outcome as the `OK: ` / `ERR: ` text Kotlin receives.
///
/// A panic that unwinds into the JVM aborts the whole app, so every function Kotlin can call
/// goes through here.
pub fn run_guarded<F>(body: F) -> String
where
    F: FnOnce() -> Result<String, String>,
{
    match catch_panic(body) {
        Ok(detail) => format!("OK: {detail}"),
        Err(detail) => format!("ERR: {detail}"),
    }
}

/// The guard for functions that do not answer in text: a panic becomes `Err("panic: ...")`.
pub fn catch_panic<T, F>(body: F) -> Result<T, String>
where
    F: FnOnce() -> Result<T, String>,
{
    // The closure's state is dropped right after a panic and never observed again, so a broken
    // invariant inside it cannot leak out.
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(result) => result,
        Err(payload) => Err(format!("panic: {}", panic_message(payload.as_ref()))),
    }
}

/// Locks a slot that holds a loaded model.
///
/// A panic while the lock was held poisons it. The panic guard has already turned that panic into
/// the failed call's error, so the poison only says that the model in the slot may have stopped in
/// the middle of a call. Such a model is not trusted again: it is taken out and dropped, the poison
/// is cleared, and the caller gets the slot empty, as after an unload. The slot itself is
/// consistent at that point because every write to it is one whole assignment (`Some(model)` after
/// the model was built, or `take()`), so a panic leaves either the old value or the new one, never
/// a half-written `Option`; whatever partial state a panic left lives inside the model that is
/// dropped here. The slot is emptied before the model is dropped, so a panic in the model's own
/// drop poisons the lock again with the slot already empty, and the next call recovers the same
/// way.
pub(crate) fn lock_slot<T>(slot: &'static Mutex<Option<T>>) -> MutexGuard<'static, Option<T>> {
    slot.lock()
        .unwrap_or_else(|poisoned| recover_slot(slot, poisoned))
}

/// [`lock_slot`]'s recovery, for a caller that met the poison through `try_lock`.
pub(crate) fn recover_slot<T>(
    slot: &'static Mutex<Option<T>>,
    poisoned: PoisonError<MutexGuard<'static, Option<T>>>,
) -> MutexGuard<'static, Option<T>> {
    let mut guard = poisoned.into_inner();
    let untrusted = guard.take();
    slot.clear_poison();
    drop(untrusted);
    guard
}

fn panic_message(payload: &(dyn Any + Send)) -> &str {
    if let Some(message) = payload.downcast_ref::<&str>() {
        message
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message
    } else {
        "non-text panic payload"
    }
}

#[cfg(test)]
mod tests {
    use super::{catch_panic, run_guarded};

    #[test]
    fn catch_panic_passes_results_and_catches_panics() {
        assert_eq!(catch_panic(|| Ok::<i32, String>(3)), Ok(3));
        assert_eq!(
            catch_panic(|| Err::<i32, String>("no".to_owned())),
            Err("no".to_owned())
        );
        assert_eq!(
            catch_panic::<i32, _>(|| panic!("boom")),
            Err("panic: boom".to_owned())
        );
    }

    #[test]
    fn ok_result_is_prefixed() {
        assert_eq!(run_guarded(|| Ok("fine".to_owned())), "OK: fine");
    }

    #[test]
    fn err_result_is_prefixed() {
        assert_eq!(run_guarded(|| Err("broken".to_owned())), "ERR: broken");
    }

    #[test]
    fn literal_panic_is_caught() {
        assert_eq!(run_guarded(|| panic!("boom")), "ERR: panic: boom");
    }

    #[test]
    fn formatted_panic_is_caught() {
        let value = 7;
        assert_eq!(
            run_guarded(|| panic!("value {value}")),
            "ERR: panic: value 7",
        );
    }

    #[test]
    fn non_text_panic_is_caught() {
        assert_eq!(
            run_guarded(|| std::panic::panic_any(42_u32)),
            "ERR: panic: non-text panic payload",
        );
    }
}
