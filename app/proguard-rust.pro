# Issue #568, rust build type only. libfga_ffi.so exports Java_io_github_fate_1grand_1automata_sensing_RustSensing_*,
# and JNI binds each native method by its class name, method name and descriptor. Keep the class and
# its native methods named as written, whether or not product code calls them yet.
-keep class io.github.fate_grand_automata.sensing.RustSensing {
    public static final io.github.fate_grand_automata.sensing.RustSensing INSTANCE;
    native <methods>;
}
