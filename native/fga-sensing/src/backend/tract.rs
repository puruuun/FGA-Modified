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
use crate::preprocess::{InputTensor, MIN_MODEL_WIDTH, MODEL_HEIGHT, check_min_width};

type Plan = Arc<TypedRunnableModel>;

const MAX_PLANS: usize = 3;

/// The largest thread count accepted, the same bound as the ONNX Runtime probe's: above the core
/// count of current phone CPUs, while a mistaken argument cannot ask for thousands of threads.
pub const MAX_TRACT_THREADS: usize = 16;

/// How a [`TractBackend`] is set up (issue #549). The default is what [`Backend::load_file`]
/// gives: the 320 plan at load and tract's single-threaded kernels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TractSettings {
    /// The input width planned at load time; set it to the recogniser's minimum width, which is
    /// the width every short crop gets.
    pub min_width: usize,
    /// Threads for tract's matrix kernels; 1 runs them on the calling thread. More than one needs
    /// the `tract-threads` feature, which builds tract-linalg with `multithread-mm` and gives the
    /// backend a rayon pool of this many threads of its own.
    pub threads: usize,
}

impl Default for TractSettings {
    fn default() -> Self {
        Self {
            min_width: MIN_MODEL_WIDTH,
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
/// minimum width (320 by default) at its height gives that width, which is planned at load time;
/// wider crops get a plan of their own on first use, kept for later calls.
pub struct TractBackend {
    model: InferenceModel,
    plans: Vec<(usize, Plan)>,
    characters: CharacterList,
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
        self.plans.iter().map(|(width, _)| *width).collect()
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
            plans: vec![(settings.min_width, plan)],
            characters,
            threads: settings.threads,
            // Last, so a model that fails to load starts no threads.
            #[cfg(feature = "tract-threads")]
            executor: executor(settings.threads)?,
        })
    }

    fn plan(&mut self, width: usize) -> Result<Plan, Error> {
        if let Some((_, plan)) = self.plans.iter().find(|(w, _)| *w == width) {
            return Ok(plan.clone());
        }
        let plan = plan_for(&self.model, width)?;
        // Each plan carries its own optimised graph, so only a few stay; the load-time one is
        // never dropped.
        if self.plans.len() >= MAX_PLANS {
            self.plans.remove(1);
        }
        self.plans.push((width, plan.clone()));
        Ok(plan)
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
        // call and per thread, and no other tract model in the process changes how it runs.
        #[cfg(feature = "tract-threads")]
        let outputs = multithread_tract_scope(self.executor.clone(), || plan.run(inputs));
        #[cfg(not(feature = "tract-threads"))]
        let outputs = plan.run(inputs);
        let outputs = outputs.map_err(backend_error)?;
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
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(min_width: usize, threads: usize) -> TractSettings {
        TractSettings { min_width, threads }
    }

    #[test]
    fn the_default_is_320_on_one_thread() {
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
