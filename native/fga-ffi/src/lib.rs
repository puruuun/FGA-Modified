//! The Kotlin boundary of the FGA Rust core.
//!
//! It carries the stage 1-0 probes of design 0025 (device-test code only) and the support-score
//! engine for product code ([`score_engine`], issue #568), which keeps its own state.

mod guard;
mod jni_exports;
pub mod probes;
mod product_jni;
pub mod score_engine;

pub use guard::{catch_panic, run_guarded};
