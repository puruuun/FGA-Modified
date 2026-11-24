"""Checks the skipped-case rule of run.py's summary: `py -3 -m unittest tools/device-qa/test_run.py`."""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import run


def result(name, actual):
    return {"name": name, "passed": True, "actual": actual}


TRACT = result("tract-recognise", {"equalToPyText": 370})
TRACT_SKIPPED = result("tract-recognise", {"skipped": "libfga_ffi.so is not packaged; build with -Pfga.native=true"})
ORT = result("ort-recognise", {"equalToPyText": 370, "threads": 0})
ORT_SKIPPED = result("ort-recognise", {"requires": "ort", "skipped": "libfga_ffi.so was built without the ort-probe feature"})
THREADS = result("tract-threads-2", {"equalToPyText": 370, "threads": 2})
THREADS_SKIPPED = result("tract-threads-2", {"requires": "tract-threads", "skipped": "libfga_ffi.so was built without the tract-threads feature"})
PING = result("ping", {"test": "ping"})
CRYPTO = result("ringProbe", {"test": "ringProbe"})
CRYPTO_SKIPPED = result("ringProbe", {"test": "ringProbe", "requires": "crypto-probes", "skipped": "libfga_ffi.so was built without the crypto-probes feature"})


class SummaryTest(unittest.TestCase):
    def passes(self, results, **flags):
        with tempfile.TemporaryDirectory() as out:
            return run.write_summary({"passed": True, "results": results}, Path(out), "ocrProbe", "serial", "adb", [], **flags)

    def test_without_flags_skips_pass(self):
        self.assertTrue(self.passes([TRACT_SKIPPED, ORT_SKIPPED]))

    def test_native_fails_on_a_skipped_tract_case(self):
        self.assertFalse(self.passes([TRACT_SKIPPED], require_native=True))

    def test_native_alone_allows_skipped_ort_cases(self):
        self.assertTrue(self.passes([TRACT, ORT_SKIPPED], require_native=True))

    def test_native_ort_fails_on_a_skipped_ort_case(self):
        self.assertFalse(self.passes([TRACT, ORT_SKIPPED], require_native=True, require_ort=True))

    def test_native_ort_fails_on_a_skipped_tract_case(self):
        self.assertFalse(self.passes([TRACT_SKIPPED, ORT], require_native=True, require_ort=True))

    def test_native_ort_passes_when_everything_ran(self):
        self.assertTrue(self.passes([TRACT, ORT], require_native=True, require_ort=True))

    def test_native_alone_allows_skipped_thread_cases(self):
        self.assertTrue(self.passes([TRACT, THREADS_SKIPPED], require_native=True))

    def test_native_ort_allows_skipped_thread_cases(self):
        self.assertTrue(self.passes([TRACT, ORT, THREADS_SKIPPED], require_native=True, require_ort=True))

    def test_native_threads_fails_on_a_skipped_thread_case(self):
        self.assertFalse(self.passes([TRACT, THREADS_SKIPPED], require_native=True, require_threads=True))

    def test_native_threads_allows_skipped_ort_cases(self):
        self.assertTrue(self.passes([TRACT, ORT_SKIPPED, THREADS], require_native=True, require_threads=True))

    def test_native_threads_fails_on_a_skipped_tract_case(self):
        self.assertFalse(self.passes([TRACT_SKIPPED, THREADS], require_native=True, require_threads=True))

    def test_native_threads_passes_when_everything_ran(self):
        self.assertTrue(self.passes([TRACT, THREADS], require_native=True, require_threads=True))

    def test_native_alone_allows_skipped_crypto_probes(self):
        self.assertTrue(self.passes([PING, CRYPTO_SKIPPED], require_native=True))

    def test_native_crypto_fails_on_a_skipped_crypto_probe(self):
        self.assertFalse(self.passes([PING, CRYPTO_SKIPPED], require_native=True, require_crypto=True))

    def test_native_fails_when_only_crypto_probes_ran_without_the_library(self):
        missing = result("ringProbe", {"test": "ringProbe", "skipped": "libfga_ffi.so is not packaged; build with -Pfga.native=true"})
        self.assertFalse(self.passes([missing], require_native=True))

    def test_native_crypto_passes_when_everything_ran(self):
        self.assertTrue(self.passes([PING, CRYPTO], require_native=True, require_crypto=True))


class GradleCommandTest(unittest.TestCase):
    def command(self, **flags):
        seen = []

        class Done:
            returncode = 0

        def fake_run(cmd, cwd):
            seen.append(cmd)
            return Done()

        original = run.subprocess.run
        run.subprocess.run = fake_run
        try:
            run.gradle_build(**flags)
        finally:
            run.subprocess.run = original
        return seen[0]

    def test_threads_property_only_with_the_flag(self):
        self.assertNotIn("-Pfga.native.threads=true", self.command(native=True))
        self.assertIn("-Pfga.native.threads=true", self.command(native=True, native_threads=True))
        self.assertNotIn("-Pfga.native.ort=true", self.command(native=True, native_threads=True))

    def test_crypto_property_only_with_the_flag(self):
        self.assertNotIn("-Pfga.native.crypto=true", self.command(native=True))
        self.assertIn("-Pfga.native.crypto=true", self.command(native=True, native_crypto=True))

    def test_without_a_model_no_model_property(self):
        cmd = self.command(native=True)
        self.assertIn("-Pfga.native=true", cmd)
        self.assertFalse(any(a.startswith("-Pfga.ocr.model") for a in cmd))

    def test_model_path_is_passed_absolute(self):
        cmd = self.command(native=True, ocr_model=Path("build/m.onnx"))
        model = [a for a in cmd if a.startswith("-Pfga.ocr.model=")]
        self.assertEqual(len(model), 1)
        self.assertTrue(Path(model[0].split("=", 1)[1]).is_absolute())

    def test_model_without_native_stops(self):
        with self.assertRaises(SystemExit):
            self.command(native=False, ocr_model=Path("m.onnx"))


if __name__ == "__main__":
    unittest.main()
