//! The intra-op thread count reaches ONNX Runtime (issue #535). ONNX Runtime's threading
//! documentation: the intra-op pool of N threads counts the calling thread, so a session built
//! with N creates N - 1 threads of its own, kept until the session is dropped. This counts the
//! process's threads in /proc/self/task around a load and a read; the counting runs on Linux (CI)
//! and Android only. Elsewhere the same loads and reads run with a fixed counter. It needs no
//! local files: the model is a tiny one built here.
//!
//! One test only: the thread count is process-wide, and the test harness runs every test on a
//! thread of its own, so a second test here would change the count while this one measures.
//! The counting helpers are tested in ort_thread_helpers.rs for that reason.

#![cfg(feature = "ort")]

#[cfg(test)]
#[path = "support/thread_count.rs"]
mod thread_count;

#[cfg(test)]
mod threads {
    use std::num::NonZeroUsize;
    use std::path::{Path, PathBuf};
    use std::time::Duration;

    use fga_sensing::Recognizer;
    use fga_sensing::backend::OrtBackend;
    use fga_sensing::image::GrayImage;

    use super::thread_count::{difference, settle};

    /// How long a dropped session's threads may stay listed. `pthread_join` can return before
    /// the kernel removes the thread's /proc/self/task entry, so a count read right after a drop
    /// can still include them.
    const SETTLE_ATTEMPTS: usize = 100;
    const SETTLE_PAUSE: Duration = Duration::from_millis(20);

    /// Protobuf wire format, just enough for the ONNX messages below.
    fn varint(mut value: u64, out: &mut Vec<u8>) {
        loop {
            let byte = (value & 0x7f) as u8;
            value >>= 7;
            if value == 0 {
                out.push(byte);
                return;
            }
            out.push(byte | 0x80);
        }
    }

    fn int_field(field: u64, value: u64, out: &mut Vec<u8>) {
        varint(field << 3, out);
        varint(value, out);
    }

    fn bytes_field(field: u64, value: &[u8], out: &mut Vec<u8>) {
        varint((field << 3) | 2, out);
        varint(value.len() as u64, out);
        out.extend_from_slice(value);
    }

    /// ValueInfoProto for a float tensor of shape [1, 3, 48, "width"], the recogniser's input.
    fn value_info(name: &str) -> Vec<u8> {
        let mut shape = Vec::new();
        for dim in [1u64, 3, 48] {
            let mut d = Vec::new();
            int_field(1, dim, &mut d); // dim_value
            bytes_field(1, &d, &mut shape); // TensorShapeProto.dim
        }
        let mut width = Vec::new();
        bytes_field(2, b"width", &mut width); // dim_param
        bytes_field(1, &width, &mut shape);
        let mut tensor = Vec::new();
        int_field(1, 1, &mut tensor); // elem_type FLOAT
        bytes_field(2, &shape, &mut tensor);
        let mut ty = Vec::new();
        bytes_field(1, &tensor, &mut ty); // TypeProto.tensor_type
        let mut info = Vec::new();
        bytes_field(1, name.as_bytes(), &mut info);
        bytes_field(2, &ty, &mut info);
        info
    }

    /// One Identity node, with the character list the backend requires in its metadata.
    fn tiny_model() -> Vec<u8> {
        let mut node = Vec::new();
        bytes_field(1, b"x", &mut node);
        bytes_field(2, b"y", &mut node);
        bytes_field(4, b"Identity", &mut node);
        let mut graph = Vec::new();
        bytes_field(1, &node, &mut graph);
        bytes_field(2, b"g", &mut graph);
        bytes_field(11, &value_info("x"), &mut graph);
        bytes_field(12, &value_info("y"), &mut graph);
        let mut opset = Vec::new();
        int_field(2, 13, &mut opset);
        let mut metadata = Vec::new();
        bytes_field(
            1,
            fga_sensing::charset::METADATA_KEY.as_bytes(),
            &mut metadata,
        );
        bytes_field(2, b"0\n1", &mut metadata);
        let mut model = Vec::new();
        int_field(1, 8, &mut model); // ir_version
        bytes_field(7, &graph, &mut model);
        bytes_field(8, &opset, &mut model);
        bytes_field(14, &metadata, &mut model);
        model
    }

    /// The model file, removed however the test ends.
    struct TempModel(PathBuf);

    impl TempModel {
        fn new() -> Self {
            let path =
                std::env::temp_dir().join(format!("fga-ort-threads-{}.onnx", std::process::id()));
            std::fs::write(&path, tiny_model()).unwrap();
            Self(path)
        }
    }

    impl Drop for TempModel {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    /// Threads added by a load and still there after a read, against the settled count before
    /// the load. ONNX Runtime's documentation says nothing of creating pool threads later, and a
    /// thread is listed in /proc/self/task as soon as the call that creates it returns, so the
    /// counts after the load and the read are taken without waiting.
    fn created_by(
        model: &Path,
        threads: Option<NonZeroUsize>,
        baseline: usize,
        count: &mut impl FnMut() -> usize,
    ) -> (i64, i64) {
        let before = settle(&mut *count, baseline, SETTLE_ATTEMPTS, SETTLE_PAUSE).unwrap();
        let mut recognizer =
            Recognizer::new(OrtBackend::load_file_with_threads(model, threads).unwrap());
        let after_load = count();
        let crop = GrayImage::new(40, 20, vec![128; 800]).unwrap();
        // Identity's output is not [1, steps, classes], so the read fails, but only after the
        // session has run.
        let read = recognizer.recognize(&crop, None).err().unwrap().to_string();
        assert!(read.contains("expected an output of shape"), "{read}");
        let after_read = count();
        drop(recognizer);
        (
            difference(before, after_load),
            difference(before, after_read),
        )
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn process_threads() -> usize {
        std::fs::read_dir("/proc/self/task").unwrap().count()
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn intra_op_thread_count_reaches_onnx_runtime() {
        let model = TempModel::new();
        let mut count = process_threads;

        // The first load also builds the process's ONNX Runtime environment. With one thread
        // it starts no pool thread, so the count after it is the baseline for every measurement.
        drop(OrtBackend::load_file_with_threads(&model.0, NonZeroUsize::new(1)).unwrap());
        let baseline = count();

        let one = created_by(&model.0, NonZeroUsize::new(1), baseline, &mut count);
        let three = created_by(&model.0, NonZeroUsize::new(3), baseline, &mut count);
        let default = created_by(&model.0, None, baseline, &mut count);
        // An observation only: the default is one thread per physical core as ONNX Runtime
        // detects it, which neither std nor a container's CPU quota tells reliably.
        println!(
            "threads created (load, read) over baseline {baseline}: one {one:?}, three {three:?}, default {default:?}"
        );

        // One intra-op thread is the calling thread: no pool thread. Three: two pool threads.
        // Ignoring the argument gives the default pool for both, which cannot be 0 and 2 at once.
        assert_eq!(one, (0, 0), "one intra-op thread");
        assert_eq!(three, (2, 2), "three intra-op threads");
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    #[test]
    fn intra_op_thread_count_reaches_onnx_runtime() {
        // No thread list to read here: the same loads and reads run with a fixed count, so the
        // model, the loads and the read assertions are checked on every platform.
        let model = TempModel::new();
        let mut count = || 7;
        for threads in [NonZeroUsize::new(1), NonZeroUsize::new(3), None] {
            assert_eq!(created_by(&model.0, threads, 7, &mut count), (0, 0));
        }
        println!(
            "ort thread count test: thread counting skipped, it reads /proc/self/task, which only Linux and Android have; the tiny model loaded and ran"
        );
    }
}
