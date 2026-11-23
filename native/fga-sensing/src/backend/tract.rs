use std::path::Path;
use std::sync::Arc;

#[cfg(feature = "tract-threads")]
use tract_linalg::multithread::{Executor, multithread_tract_scope};
use tract_onnx::pb::ModelProto;
use tract_onnx::prelude::*;

use super::{Backend, backend_error, probabilities_from};
use crate::Error;
use crate::charset::{CharacterList, METADATA_KEY};
use crate::ctc::Probabilities;
use crate::preprocess::{InputTensor, MODEL_HEIGHT, check_min_width};

type Plan = Arc<TypedRunnableModel>;

const MAX_PLANS: usize = 3;

/// The largest thread count accepted, the same bound as the ONNX Runtime probe's: above the core
/// count of current phone CPUs, while a mistaken argument cannot ask for thousands of threads.
pub const MAX_TRACT_THREADS: usize = 16;

/// The input width the support-score reader plans at load: RapidOCR's 320. The reader gives the
/// model the crop without its label rows (issue #639), and only with that trim and this width did
/// every real device crop of both servers read under shifted and rescaled copies as well;
/// narrower widths lose reads (docs/agents/rust-native.md).
pub const READER_MIN_WIDTH: usize = 320;

/// How a [`TractBackend`] is set up (issue #549). The default is what [`Backend::load_file`]
/// gives: the [`READER_MIN_WIDTH`] plan at load and tract's single-threaded kernels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TractSettings {
    /// The input width planned at load time. `Recognizer::new` pads short crops to it; a
    /// recogniser given another width with `with_min_width` makes the backend plan that one too.
    pub min_width: usize,
    /// Threads for tract's matrix kernels; 1 runs them on the calling thread. More than one needs
    /// the `tract-threads` feature, which builds tract-linalg with `multithread-mm` and gives the
    /// backend a rayon pool of this many threads of its own.
    pub threads: usize,
}

impl Default for TractSettings {
    fn default() -> Self {
        Self {
            min_width: READER_MIN_WIDTH,
            threads: 1,
        }
    }
}

impl TractSettings {
    /// Both values are checked before any file is read or any thread is started.
    pub fn check(&self) -> Result<(), Error> {
        check_min_width(self.min_width)?;
        if self.threads == 0 || self.threads > MAX_TRACT_THREADS {
            return Err(Error::InvalidThreadCount {
                threads: self.threads,
                max: MAX_TRACT_THREADS,
            });
        }
        if self.threads > 1 && !cfg!(feature = "tract-threads") {
            return Err(Error::ThreadsNotBuilt {
                threads: self.threads,
            });
        }
        Ok(())
    }
}

/// The model through `tract-onnx`.
///
/// The input width is pinned per plan: tract also runs the model with a symbolic width, but on the
/// PC that took 59 ms per crop against 25 ms pinned (issue #524). Every crop narrower than the
/// minimum width at its height gives that width, which is planned at load time and which
/// [`Backend::min_width`] reports, so a recogniser pads to it; wider crops get a plan of their own
/// on first use, kept for later calls.
pub struct TractBackend {
    model: InferenceModel,
    plans: PlanCache<Plan>,
    characters: CharacterList,
    min_width: usize,
    threads: usize,
    #[cfg(feature = "tract-threads")]
    executor: Executor,
}

impl TractBackend {
    pub fn load_file_with(path: &Path, settings: TractSettings) -> Result<Self, Error> {
        settings.check()?;
        let proto = tract_onnx::onnx()
            .proto_model_for_path(path)
            .map_err(backend_error)?;
        Self::from_proto(&proto, settings)
    }

    pub fn load_bytes_with(bytes: &[u8], settings: TractSettings) -> Result<Self, Error> {
        settings.check()?;
        let mut reader = bytes;
        let proto = tract_onnx::onnx()
            .proto_model_for_read(&mut reader)
            .map_err(backend_error)?;
        Self::from_proto(&proto, settings)
    }

    /// The thread count the kernels run on, as set at load.
    pub fn threads(&self) -> usize {
        self.threads
    }

    /// The input widths that have a plan, the load-time one first.
    pub fn planned_widths(&self) -> Vec<usize> {
        self.plans.widths()
    }

    fn from_proto(proto: &ModelProto, settings: TractSettings) -> Result<Self, Error> {
        let text = proto
            .metadata_props
            .iter()
            .find(|entry| entry.key == METADATA_KEY)
            .map(|entry| entry.value.as_str())
            .ok_or(Error::MissingCharacterList)?;
        let characters = CharacterList::from_metadata(text)?;
        // The export records intermediate shapes with its own symbols (`DynamicDimension.0`);
        // tract cannot unify those with a pinned input shape, so it re-infers them instead.
        let model = tract_onnx::onnx()
            .with_ignore_value_info(true)
            .model_for_proto_model(proto)
            .map_err(backend_error)?;
        let plan = plan_for(&model, settings.min_width)?;
        Ok(Self {
            model,
            plans: PlanCache::new(settings.min_width, plan),
            characters,
            min_width: settings.min_width,
            threads: settings.threads,
            // Last, so a model that fails to load starts no threads.
            #[cfg(feature = "tract-threads")]
            executor: executor(settings.threads)?,
        })
    }

    fn plan(&mut self, width: usize) -> Result<Plan, Error> {
        let model = &self.model;
        self.plans
            .get_or_insert_with(width, || plan_for(model, width))
    }
}

/// Plans by input width, at most [`MAX_PLANS`]. Each plan carries its own optimised graph, so only
/// a few stay; the load-time one (the width every short crop gets) is never dropped, and of the
/// others the oldest goes first.
struct PlanCache<P> {
    entries: Vec<(usize, P)>,
}

impl<P: Clone> PlanCache<P> {
    fn new(width: usize, plan: P) -> Self {
        Self {
            entries: vec![(width, plan)],
        }
    }

    fn get_or_insert_with(
        &mut self,
        width: usize,
        make: impl FnOnce() -> Result<P, Error>,
    ) -> Result<P, Error> {
        if let Some((_, plan)) = self.entries.iter().find(|(w, _)| *w == width) {
            return Ok(plan.clone());
        }
        let plan = make()?;
        if self.entries.len() >= MAX_PLANS {
            self.entries.remove(1);
        }
        self.entries.push((width, plan.clone()));
        Ok(plan)
    }

    fn widths(&self) -> Vec<usize> {
        self.entries.iter().map(|(width, _)| *width).collect()
    }
}

/// Runs `run` with `executor` installed for this thread. tract-linalg's `multithread_tract_scope`
/// puts the thread's previous executor back only when its closure returns, so a panic inside
/// tract would leave this pool installed on the calling (JNI) thread for every later tract call
/// there that sets none of its own. The panic is therefore caught inside the scope, so that the
/// scope returns and restores, and raised again after it.
#[cfg(feature = "tract-threads")]
fn in_scope<T>(executor: &Executor, run: impl FnOnce() -> TractResult<T>) -> Result<T, Error> {
    use std::panic::{AssertUnwindSafe, catch_unwind, resume_unwind};
    match multithread_tract_scope(executor.clone(), || catch_unwind(AssertUnwindSafe(run))) {
        Ok(result) => result.map_err(backend_error),
        // Not a new panic: the one tract raised continues, as it does in a build without the
        // feature, so the caller's panic guard reports it and fga-ffi's recogniser lock is
        // poisoned instead of a recogniser that stopped mid-run being used again.
        Err(payload) => resume_unwind(payload),
    }
}

/// One thread is tract's own single-threaded path, so a build with the feature runs one thread
/// exactly as a build without it. tract-linalg's `Executor::multithread` panics when the pool
/// cannot be built, so the pool is built here, where that is an error.
#[cfg(feature = "tract-threads")]
fn executor(threads: usize) -> Result<Executor, Error> {
    if threads <= 1 {
        return Ok(Executor::SingleThread);
    }
    rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .thread_name(|index| format!("fga-tract-{index}"))
        .build()
        .map(|pool| Executor::MultiThread(Arc::new(pool)))
        .map_err(backend_error)
}

fn plan_for(model: &InferenceModel, width: usize) -> Result<Plan, Error> {
    let fact = InferenceFact::dt_shape(f32::datum_type(), tvec!(1, 3, MODEL_HEIGHT, width));
    model
        .clone()
        .with_input_fact(0, fact)
        .and_then(|model| model.into_optimized())
        .and_then(|model| model.into_runnable())
        .map_err(backend_error)
}

impl Backend for TractBackend {
    fn load_file(path: &Path) -> Result<Self, Error> {
        Self::load_file_with(path, TractSettings::default())
    }

    fn load_bytes(bytes: &[u8]) -> Result<Self, Error> {
        Self::load_bytes_with(bytes, TractSettings::default())
    }

    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error> {
        let plan = self.plan(input.width())?;
        let tensor = Tensor::from_shape(&input.shape(), input.data()).map_err(backend_error)?;
        let inputs = tvec!(tensor.into_tvalue());
        // tract-linalg reads the executor from a thread-local override first, so it is set per
        // call and per thread, for the one-thread backend too (`SingleThread`), and no other
        // tract model in the process changes how it runs. Without the feature nothing here sets
        // an override, so tract runs on its process default, `SingleThread`.
        #[cfg(feature = "tract-threads")]
        let outputs = in_scope(&self.executor, || plan.run(inputs))?;
        #[cfg(not(feature = "tract-threads"))]
        let outputs = plan.run(inputs).map_err(backend_error)?;
        let output = outputs
            .first()
            .ok_or_else(|| Error::Backend("the model returned no output".into()))?;
        let view = output.to_plain_array_view::<f32>().map_err(backend_error)?;
        let shape = view.shape().to_vec();
        probabilities_from(&shape, view.iter().copied().collect())
    }

    fn characters(&self) -> &CharacterList {
        &self.characters
    }

    fn min_width(&self) -> usize {
        self.min_width
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(min_width: usize, threads: usize) -> TractSettings {
        TractSettings { min_width, threads }
    }

    #[test]
    fn the_default_is_320_on_one_thread() {
        assert_eq!(READER_MIN_WIDTH, 320);
        assert_eq!(TractSettings::default(), settings(320, 1));
        assert_eq!(TractSettings::default().check(), Ok(()));
    }

    #[test]
    fn the_settings_are_bounded() {
        assert_eq!(settings(8, 1).check(), Ok(()));
        assert_eq!(settings(4096, 1).check(), Ok(()));
        assert!(matches!(
            settings(7, 1).check(),
            Err(Error::InvalidMinWidth { width: 7, .. })
        ));
        assert!(matches!(
            settings(4097, 1).check(),
            Err(Error::InvalidMinWidth { width: 4097, .. })
        ));
        for threads in [0, MAX_TRACT_THREADS + 1, usize::MAX] {
            assert_eq!(
                settings(320, threads).check(),
                Err(Error::InvalidThreadCount {
                    threads,
                    max: MAX_TRACT_THREADS
                })
            );
        }
    }

    #[cfg(not(feature = "tract-threads"))]
    #[test]
    fn more_than_one_thread_needs_the_feature() {
        for threads in [2, MAX_TRACT_THREADS] {
            assert_eq!(
                settings(320, threads).check(),
                Err(Error::ThreadsNotBuilt { threads })
            );
        }
        // Refused before the file is opened: the path does not exist.
        assert!(matches!(
            TractBackend::load_file_with(Path::new("no/such/model.onnx"), settings(320, 2)),
            Err(Error::ThreadsNotBuilt { threads: 2 })
        ));
    }

    #[cfg(feature = "tract-threads")]
    #[test]
    fn the_feature_takes_more_than_one_thread() {
        assert_eq!(settings(320, 2).check(), Ok(()));
        assert_eq!(settings(320, MAX_TRACT_THREADS).check(), Ok(()));
        assert!(matches!(executor(1), Ok(Executor::SingleThread)));
        match executor(3) {
            Ok(Executor::MultiThread(pool)) => assert_eq!(pool.current_num_threads(), 3),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn the_load_time_plan_stays_and_the_oldest_other_goes() {
        let mut cache = PlanCache::new(320, "load");
        let mut made = Vec::new();
        for width in [400, 480, 400, 560, 640, 320] {
            let plan = cache
                .get_or_insert_with(width, || {
                    made.push(width);
                    Ok("planned")
                })
                .unwrap();
            assert_eq!(plan, if width == 320 { "load" } else { "planned" });
        }
        // 400 was reused, then dropped for 560; 480 was dropped for 640.
        assert_eq!(made, vec![400, 480, 560, 640]);
        assert_eq!(cache.widths(), vec![320, 560, 640]);
    }

    #[test]
    fn a_failed_plan_is_not_kept() {
        let mut cache = PlanCache::new(320, 1);
        assert_eq!(
            cache.get_or_insert_with(400, || Err(Error::Backend("no".into()))),
            Err(Error::Backend("no".into()))
        );
        assert_eq!(cache.widths(), vec![320]);
    }

    #[test]
    fn a_failed_plan_evicts_nothing_from_a_full_cache() {
        let mut cache = PlanCache::new(320, 1);
        for width in [400, 480] {
            cache.get_or_insert_with(width, || Ok(2)).unwrap();
        }
        assert_eq!(cache.widths(), vec![320, 400, 480]);
        assert_eq!(
            cache.get_or_insert_with(560, || Err(Error::Backend("no".into()))),
            Err(Error::Backend("no".into()))
        );
        assert_eq!(cache.widths(), vec![320, 400, 480]);
    }

    #[cfg(feature = "tract-threads")]
    fn panic_inside_scope() -> std::thread::Result<Result<(), Error>> {
        let Ok(pool) = executor(2) else {
            panic!("no pool")
        };
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            in_scope(&pool, || panic!("inside tract"))
        }))
    }

    #[cfg(feature = "tract-threads")]
    #[test]
    fn a_panic_inside_the_scope_reaches_the_caller() {
        let Err(payload) = panic_inside_scope() else {
            panic!("the panic was swallowed")
        };
        assert_eq!(payload.downcast_ref::<&str>(), Some(&"inside tract"));
    }

    #[cfg(feature = "tract-threads")]
    #[test]
    fn a_panic_inside_the_scope_restores_the_executor() {
        use tract_linalg::multithread::current_tract_executor;
        // Whatever reaches the caller, the pool must not stay installed on this thread.
        let _ = panic_inside_scope();
        assert!(matches!(current_tract_executor(), Executor::SingleThread));
        let Ok(pool) = executor(2) else {
            panic!("no pool")
        };
        assert_eq!(in_scope(&pool, || Ok(7)), Ok(7));
        assert!(matches!(current_tract_executor(), Executor::SingleThread));
    }

    #[test]
    fn bad_settings_are_refused_before_the_file_is_read() {
        assert!(matches!(
            TractBackend::load_file_with(Path::new("no/such/model.onnx"), settings(7, 1)),
            Err(Error::InvalidMinWidth { .. })
        ));
        assert!(matches!(
            TractBackend::load_bytes_with(&[], settings(320, 0)),
            Err(Error::InvalidThreadCount { .. })
        ));
    }
}
