use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::Mutex;

use ort::environment::Environment;
use ort::session::Session;
use ort::session::builder::SessionBuilder;
use ort::value::TensorRef;

use super::{Backend, backend_error, dimensions, probabilities_from};
use crate::Error;
use crate::charset::{CharacterList, METADATA_KEY};
use crate::ctc::Probabilities;
use crate::preprocess::InputTensor;

/// The model through ONNX Runtime, the runtime the Python check used.
pub struct OrtBackend {
    session: Session,
    characters: CharacterList,
    intra_threads: Option<NonZeroUsize>,
}

/// `ort` allows one environment per process, ever: once one has been built, building another
/// fails even after the first is dropped. So the first is kept here and shared by every later
/// load, and it stays for the life of the process, which also keeps it alive longer than every
/// session as ONNX Runtime wants (issue #535: the device suite loads the model
/// several times in one process).
static ENVIRONMENT: Mutex<Option<Environment>> = Mutex::new(None);

fn environment() -> Result<Environment, Error> {
    let mut slot = ENVIRONMENT
        .lock()
        .map_err(|_| Error::Backend("the ONNX Runtime environment lock is poisoned".into()))?;
    if let Some(environment) = slot.as_ref() {
        return Ok(environment.clone());
    }
    // ONNX Runtime on Windows sends usage telemetry unless told not to.
    let environment = ort::init()
        .with_telemetry(false)
        .build()
        .map_err(backend_error)?;
    *slot = Some(environment.clone());
    Ok(environment)
}

impl OrtBackend {
    /// `intra_threads` is the size of ONNX Runtime's intra-op pool, the calling thread included.
    /// `None` keeps ONNX Runtime's own default (its documentation: one thread per physical core),
    /// which is what the PC tools and [`Backend::load_file`] use. Issue #535 sets 1 for the
    /// like-for-like comparison with tract, which runs on one thread.
    pub fn load_file_with_threads(
        path: &Path,
        intra_threads: Option<NonZeroUsize>,
    ) -> Result<Self, Error> {
        Self::load(intra_threads, |mut builder| builder.commit_from_file(path))
    }

    /// The intra-op thread count this session was built with; `None` is ONNX Runtime's default.
    pub fn intra_threads(&self) -> Option<NonZeroUsize> {
        self.intra_threads
    }

    fn load(
        intra_threads: Option<NonZeroUsize>,
        commit: impl FnOnce(SessionBuilder) -> ort::Result<Session>,
    ) -> Result<Self, Error> {
        let environment = environment()?;
        let mut builder = Session::builder(&environment).map_err(backend_error)?;
        if let Some(threads) = intra_threads {
            builder = builder
                .with_intra_threads(threads.get())
                .map_err(backend_error)?;
        }
        let session = commit(builder).map_err(backend_error)?;
        let text = session
            .metadata()
            .map_err(backend_error)?
            .custom(METADATA_KEY)
            .ok_or(Error::MissingCharacterList)?;
        let characters = CharacterList::from_metadata(&text)?;
        Ok(Self {
            session,
            characters,
            intra_threads,
        })
    }
}

impl Backend for OrtBackend {
    fn load_file(path: &Path) -> Result<Self, Error> {
        Self::load_file_with_threads(path, None)
    }

    fn load_bytes(bytes: &[u8]) -> Result<Self, Error> {
        Self::load(None, |mut builder| builder.commit_from_memory(bytes))
    }

    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error> {
        let tensor =
            TensorRef::from_array_view((input.shape(), input.data())).map_err(backend_error)?;
        let outputs = self
            .session
            .run(ort::inputs![tensor])
            .map_err(backend_error)?;
        let (_, output) = outputs
            .iter()
            .next()
            .ok_or_else(|| Error::Backend("the model returned no output".into()))?;
        let (shape, data) = output.try_extract_tensor::<f32>().map_err(backend_error)?;
        probabilities_from(&dimensions(shape)?, data.to_vec())
    }

    fn characters(&self) -> &CharacterList {
        &self.characters
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_second_load_reuses_the_environment() {
        let missing = Path::new("no/such/model.onnx");
        let first = OrtBackend::load_file(missing).err().unwrap().to_string();
        let second = OrtBackend::load_file_with_threads(missing, NonZeroUsize::new(1))
            .err()
            .unwrap()
            .to_string();
        assert_eq!(first, second);
        assert!(!second.contains("environment"), "{second}");
    }
}
