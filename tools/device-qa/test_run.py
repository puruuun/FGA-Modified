"""Checks the skipped-case rule of run.py's summary: `py -3 -m unittest tools/device-qa/test_run.py`."""

import os
import sys
import io
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


class Completed:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode, self.stdout, self.stderr = returncode, stdout, stderr


UNREACHABLE = "adb: error: failed to get feature set: cannot connect to daemon at tcp:5037: timed out (10060)\n"


class AdbRetryTest(unittest.TestCase):
    def setUp(self):
        self.calls, self.pauses, self.script = [], [], []
        self.saved = (run.run, run.sleep, sys.stderr)
        run.run = self.fake_run
        run.sleep = self.pauses.append
        sys.stderr = self.err = io.StringIO()

    def tearDown(self):
        run.run, run.sleep, sys.stderr = self.saved

    def fake_run(self, cmd, **kwargs):
        self.calls.append(cmd)
        return self.script.pop(0) if len(self.script) > 1 else self.script[0]

    def test_unreachable_once_then_ok_is_retried(self):
        self.script = [Completed(1, "", UNREACHABLE), Completed(0, "done")]
        self.assertEqual(run.adb("adb", "s", "push", "a", "b", retry=True), "done")
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(self.pauses, [5])
        self.assertIn("adb push", self.err.getvalue())
        self.assertNotIn("a b", self.err.getvalue())

    def test_unreachable_always_fails_after_four_attempts_with_the_original_message(self):
        self.script = [Completed(1, "", UNREACHABLE)]
        with self.assertRaises(SystemExit) as stop:
            run.adb("adb", "s", "push", "a", "b", retry=True)
        self.assertEqual(len(self.calls), 4)
        self.assertEqual(self.pauses, [5, 15, 30])
        self.assertIn("cannot connect to daemon", str(stop.exception))

    def test_every_unreachable_message_counts(self):
        for message in run.SERVER_UNREACHABLE:
            self.assertTrue(run.server_unreachable(f"* {message} *"), message)

    def test_unrelated_failure_is_not_retried(self):
        self.script = [Completed(1, "", "adb: error: device offline\n")]
        with self.assertRaises(SystemExit):
            run.adb("adb", "s", "push", "a", "b", retry=True)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.pauses, [])

    def test_call_not_marked_safe_is_not_retried(self):
        self.script = [Completed(1, "", UNREACHABLE)]
        with self.assertRaises(SystemExit):
            run.adb("adb", "s", "shell", "am", "instrument", "-w")
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.pauses, [])

    def test_instrument_is_never_retried(self):
        self.script = [Completed(1, "", UNREACHABLE)]
        with self.assertRaises(SystemExit):
            run.instrument("adb", "s", "pkg", "/sdcard/x.json")
        self.assertEqual(len(self.calls), 1)

    def test_no_server_command_is_issued(self):
        self.script = [Completed(1, "", UNREACHABLE)]
        with self.assertRaises(SystemExit):
            run.adb("adb", "s", "version", retry=True)
        for cmd in self.calls:
            self.assertFalse({"start-server", "kill-server", "reconnect", "connect"} & set(cmd))


class RunSuitesTest(unittest.TestCase):
    def run_suites(self, outcomes):
        """outcomes: suite -> True/False (summary written), or None (stops with an error before any summary)."""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)

            def run_suite(suite):
                if outcomes[suite] is None:
                    sys.exit("adb push failed")
                (out / f"summary-{suite}.md").write_text("x", encoding="utf-8")
                return outcomes[suite]

            saved = sys.stderr
            sys.stderr = io.StringIO()
            try:
                return run.run_suites(list(outcomes), run_suite, out)
            finally:
                sys.stderr = saved

    def test_all_suites_ran_and_passed(self):
        overall, not_run = self.run_suites({"support": True, "bond": True})
        self.assertEqual((overall, not_run), (True, []))
        self.assertEqual(run.exit_code(overall, not_run), 0)

    def test_failed_suite_keeps_exit_code_one(self):
        overall, not_run = self.run_suites({"support": False, "bond": True})
        self.assertEqual(not_run, [])
        self.assertEqual(run.exit_code(overall, not_run), 1)

    def test_suite_that_stopped_is_named_and_later_suites_still_run(self):
        overall, not_run = self.run_suites({"support": True, "bond": None, "fpSell": True})
        self.assertEqual(not_run, ["bond"])
        self.assertNotEqual(run.exit_code(overall, not_run), 0)

    def test_not_run_beats_a_pass(self):
        self.assertEqual(run.exit_code(True, ["bond"]), 2)


if __name__ == "__main__":
    unittest.main()
