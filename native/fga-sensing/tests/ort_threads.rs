//! The intra-op thread count reaches ONNX Runtime (issue #535). ONNX Runtime's threading
//! documentation: the intra-op pool of N threads counts the calling thread, so a session built
//! with N creates N - 1 threads of its own, kept until the session is dropped. This counts the
//! process's threads in /proc/self/task around a load and a read, so it counts on Linux (CI) and Android only
//! and says so elsewhere. It needs no local files: the model is a tiny one built here.
//!
//! One test only: the thread count is process-wide, and a second test running in parallel would
//! change it.

#![cfg(feature = "ort")]

#[cfg(test)]
mod threads {
    use std::num::NonZeroUsize;

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

    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn process_threads() -> usize {
        std::fs::read_dir("/proc/self/task").unwrap().count()
    }

    /// Threads the load created, and threads alive after a read, both against the count before
    /// the load. The read fails (Identity's output is not [1, steps, classes]), but it has run
    /// the session by then.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn created_by(model: &std::path::Path, threads: Option<NonZeroUsize>) -> (usize, usize) {
        use fga_sensing::Recognizer;
        use fga_sensing::backend::OrtBackend;
        use fga_sensing::image::GrayImage;

        let before = process_threads();
        let mut recognizer =
            Recognizer::new(OrtBackend::load_file_with_threads(model, threads).unwrap());
        let after_load = process_threads();
        let crop = GrayImage::new(40, 20, vec![128; 800]).unwrap();
        let _ = recognizer.recognize(&crop, None);
        let after_read = process_threads();
        drop(recognizer);
        (after_load - before, after_read - before)
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn intra_op_thread_count_reaches_onnx_runtime() {
        let model =
            std::env::temp_dir().join(format!("fga-ort-threads-{}.onnx", std::process::id()));
        std::fs::write(&model, tiny_model()).unwrap();

        // The first load also builds the process's ONNX Runtime environment; measured loads
        // come after it, so only the session's own threads are counted.
        created_by(&model, NonZeroUsize::new(1));

        let one = created_by(&model, NonZeroUsize::new(1));
        let three = created_by(&model, NonZeroUsize::new(3));
        let default = created_by(&model, None);
        std::fs::remove_file(&model).unwrap();
        println!("threads created (load, read): one {one:?}, three {three:?}, default {default:?}");

        // One intra-op thread is the calling thread: no pool thread. Three: two pool threads.
        // Ignoring the argument gives the default pool for both, which cannot be 0 and 2 at once.
        assert_eq!(one, (0, 0), "one intra-op thread");
        assert_eq!(three, (2, 2), "three intra-op threads");
        // The default is one thread per physical core, which std cannot tell; it can never
        // exceed the logical processors. On a single-core runner it equals the one-thread case,
        // which the two exact checks above still distinguish from an ignored argument.
        let logical = std::thread::available_parallelism().unwrap().get();
        assert!(
            default.0 < logical.max(2),
            "default created {default:?} on {logical} processors"
        );
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    #[test]
    fn intra_op_thread_count_reaches_onnx_runtime() {
        use fga_sensing::Recognizer;
        use fga_sensing::backend::OrtBackend;
        use fga_sensing::image::GrayImage;

        // The thread count needs Linux; the tiny model is still loaded and run here, so a broken
        // model encoding shows up on every platform, not only in CI.
        let model =
            std::env::temp_dir().join(format!("fga-ort-threads-{}.onnx", std::process::id()));
        std::fs::write(&model, tiny_model()).unwrap();
        let backend = OrtBackend::load_file_with_threads(&model, NonZeroUsize::new(1));
        std::fs::remove_file(&model).unwrap();
        let mut recognizer = Recognizer::new(backend.unwrap());
        let crop = GrayImage::new(40, 20, vec![128; 800]).unwrap();
        let read = recognizer.recognize(&crop, None).err().unwrap().to_string();
        assert!(read.contains("expected an output of shape"), "{read}");
        println!(
            "ort thread count test: thread counting skipped, it reads /proc/self/task, which only Linux and Android have; the tiny model loaded and ran"
        );
    }
}
