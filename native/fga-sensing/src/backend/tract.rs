use std::path::Path;
use std::sync::Arc;

use tract_onnx::pb::ModelProto;
use tract_onnx::prelude::*;

use super::{Backend, backend_error, probabilities_from};
use crate::Error;
use crate::charset::{CharacterList, METADATA_KEY};
use crate::ctc::Probabilities;
use crate::preprocess::{InputTensor, MIN_MODEL_WIDTH, MODEL_HEIGHT};

type Plan = Arc<TypedRunnableModel>;

const MAX_PLANS: usize = 3;

/// The model through `tract-onnx`.
///
/// The input width is pinned per plan: tract also runs the model with a symbolic width, but on the
/// PC that took 59 ms per crop against 25 ms pinned (issue #524). Every crop narrower than 320/48
/// of its height gives the width 320, which is planned at load time; wider crops get a plan of
/// their own on first use, kept for later calls.
pub struct TractBackend {
    model: InferenceModel,
    plans: Vec<(usize, Plan)>,
    characters: CharacterList,
}

impl TractBackend {
    fn from_proto(proto: &ModelProto) -> Result<Self, Error> {
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
        let plan = plan_for(&model, MIN_MODEL_WIDTH)?;
        Ok(Self {
            model,
            plans: vec![(MIN_MODEL_WIDTH, plan)],
            characters,
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
        let proto = tract_onnx::onnx()
            .proto_model_for_path(path)
            .map_err(backend_error)?;
        Self::from_proto(&proto)
    }

    fn load_bytes(bytes: &[u8]) -> Result<Self, Error> {
        let mut reader = bytes;
        let proto = tract_onnx::onnx()
            .proto_model_for_read(&mut reader)
            .map_err(backend_error)?;
        Self::from_proto(&proto)
    }

    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error> {
        let plan = self.plan(input.width())?;
        let tensor = Tensor::from_shape(&input.shape(), input.data()).map_err(backend_error)?;
        let outputs = plan
            .run(tvec!(tensor.into_tvalue()))
            .map_err(backend_error)?;
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
