use std::path::Path;

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
    // Kept for as long as the session; ONNX Runtime wants its environment to outlive sessions.
    _environment: Environment,
}

impl OrtBackend {
    fn load(commit: impl FnOnce(SessionBuilder) -> ort::Result<Session>) -> Result<Self, Error> {
        // ONNX Runtime on Windows sends usage telemetry unless told not to.
        let environment = ort::init()
            .with_telemetry(false)
            .build()
            .map_err(backend_error)?;
        let session = Session::builder(&environment)
            .and_then(commit)
            .map_err(backend_error)?;
        let text = session
            .metadata()
            .map_err(backend_error)?
            .custom(METADATA_KEY)
            .ok_or(Error::MissingCharacterList)?;
        let characters = CharacterList::from_metadata(&text)?;
        Ok(Self {
            session,
            characters,
            _environment: environment,
        })
    }
}

impl Backend for OrtBackend {
    fn load_file(path: &Path) -> Result<Self, Error> {
        Self::load(|mut builder| builder.commit_from_file(path))
    }

    fn load_bytes(bytes: &[u8]) -> Result<Self, Error> {
        Self::load(|mut builder| builder.commit_from_memory(bytes))
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
