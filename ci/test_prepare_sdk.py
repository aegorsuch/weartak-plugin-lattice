# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_sdk


class PrepareSdkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.target = self.root / "sdk"
        self.target.mkdir()
        self.revision = "a" * 40
        (self.root / "sdk-revision.txt").write_text(self.revision)
        (self.target / "LICENSE").write_text("synthetic license fixture")
        for field, value in (("ROOT", self.root), ("TARGET", self.target)):
            patcher = patch.object(prepare_sdk, field, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_existing_clean_pin_never_fetches_or_overwrites(self):
        with patch.object(prepare_sdk.subprocess, "check_output", side_effect=[self.revision + "\n", ""]), \
             patch.object(prepare_sdk.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
            prepare_sdk.prepare()
            run.assert_not_called()

    def test_different_or_dirty_revision_fails_without_checkout(self):
        for actual, dirty in (("b" * 40, ""), (self.revision, " M README.md")):
            with patch.object(prepare_sdk.subprocess, "check_output", side_effect=[actual, dirty]), \
                 patch.object(prepare_sdk.subprocess, "run") as run:
                with self.assertRaises(RuntimeError):
                    prepare_sdk.prepare()
                run.assert_not_called()

    def test_missing_license_fails(self):
        (self.target / "LICENSE").unlink()
        with patch.object(prepare_sdk.subprocess, "check_output", side_effect=[self.revision, ""]):
            with self.assertRaises(RuntimeError):
                prepare_sdk.prepare()

    def test_invalid_pin_is_not_executed(self):
        (self.root / "sdk-revision.txt").write_text("main; unexpected")
        with patch.object(prepare_sdk.subprocess, "run") as run:
            with self.assertRaises(RuntimeError):
                prepare_sdk.prepare()
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
