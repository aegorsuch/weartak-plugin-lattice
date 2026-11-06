# SPDX-License-Identifier: GPL-3.0-or-later
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent
SIGNER = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"


@unittest.skipUnless(os.name == "posix", "Linux CI shell contract tests")
class ReleaseContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment = os.environ.copy()
        for key in ("WEARTAK_RELEASE_KEYSTORE_B64", "RELEASE_STORE_PASSWORD",
                    "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"):
            self.environment.pop(key, None)
        self.environment.update({
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "CI_PROJECT_DIR": str(self.root),
            "CI_COMMIT_BRANCH": "main", "CI_DEFAULT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true", "CI_COMMIT_SHA": "a" * 40,
            "CI_PIPELINE_IID": "7", "CI_PIPELINE_ID": "123", "CI_JOB_ID": "456",
            "ANDROID_HOME": str(self.root / "sdk"),
            "WEARTAK_RELEASE_KEYSTORE_FILE": str(self.root / "release.keystore"),
        })

    def executable(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!/bin/sh\nset -eu\n" + text)
        path.chmod(0o700)

    def run_script(self, name):
        return subprocess.run(["sh", str(SCRIPTS / name)], env=self.environment,
                              capture_output=True, text=True)

    def signing_fixture(self):
        self.environment.update({
            "WEARTAK_RELEASE_KEYSTORE_B64": base64.b64encode(b"synthetic-keystore").decode(),
            "RELEASE_STORE_PASSWORD": "synthetic-store-secret",
            "RELEASE_KEY_ALIAS": "synthetic-alias",
            "RELEASE_KEY_PASSWORD": "synthetic-key-secret",
        })
        self.executable(self.bin / "keytool", 'printf "%s\\n" "$@" > "$CI_PROJECT_DIR/keytool-args"\n')

    def package_fixture(self, signer=SIGNER, version="7"):
        tools = self.root / "sdk" / "build-tools" / "34.0.0"
        self.executable(tools / "apksigner", f"echo 'Signer #1 certificate SHA-256 digest: {signer}'\n")
        self.executable(tools / "aapt",
                        f'''echo "package: name='com.weartak.plugin.lattice' versionCode='{version}' versionName='5.8.4'"\n''')
        apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"synthetic-apk-not-installable")

    def test_missing_signing_configuration_fails_without_exposing_values(self):
        result = self.run_script("prepare_signing.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing signing variable: WEARTAK_RELEASE_KEYSTORE_B64", result.stderr)
        self.assertFalse((self.root / "release.keystore").exists())

    def test_restore_preserves_bytes_permissions_and_keeps_password_off_command_line(self):
        self.signing_fixture()
        result = self.run_script("prepare_signing.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        store = self.root / "release.keystore"
        self.assertEqual(b"synthetic-keystore", store.read_bytes())
        self.assertEqual(0o600, store.stat().st_mode & 0o777)
        arguments = (self.root / "keytool-args").read_text()
        self.assertIn("-storepass:env\nRELEASE_STORE_PASSWORD", arguments)
        self.assertNotIn("synthetic-store-secret", arguments + result.stdout + result.stderr)

    def test_invalid_base64_leaves_no_signing_file(self):
        self.signing_fixture()
        self.environment["WEARTAK_RELEASE_KEYSTORE_B64"] = "invalid!"
        result = self.run_script("prepare_signing.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "release.keystore").exists())

    def test_existing_signing_file_is_never_overwritten(self):
        self.signing_fixture()
        store = self.root / "release.keystore"
        store.write_bytes(b"preserve")
        result = self.run_script("prepare_signing.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"preserve", store.read_bytes())

    def test_valid_release_emits_canonical_name_hash_and_exact_provenance(self):
        self.package_fixture()
        result = self.run_script("package_release.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        apk = self.root / "app/build/outputs/lattice/release/WearTAK-Plugin-Lattice-5.8.4-p7.apk"
        checksum = Path(str(apk) + ".sha256").read_text().split()[0]
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), checksum)
        provenance = dict(line.split("=", 1) for line in Path(str(apk) + ".provenance.txt").read_text().splitlines())
        self.assertEqual("a" * 40, provenance["source_sha"])
        self.assertEqual("123", provenance["pipeline_id"])
        self.assertEqual("456", provenance["job_id"])
        self.assertEqual("7", provenance["version_code"])
        self.assertEqual(SIGNER, provenance["signer_sha256"])

    def test_wrong_signer_or_multiple_signers_cannot_emit_artifacts(self):
        for signer in ("0" * 64, SIGNER + "\nSigner #2 certificate SHA-256 digest: " + SIGNER):
            with self.subTest(signer=signer):
                tools = self.root / "sdk"
                if tools.exists():
                    shutil.rmtree(tools)
                apk_folder = self.root / "app"
                if apk_folder.exists():
                    shutil.rmtree(apk_folder)
                self.package_fixture(signer=signer)
                result = self.run_script("package_release.sh")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Unexpected release signing certificate", result.stderr)
                self.assertFalse((self.root / "app/build/outputs/lattice").exists())

    def test_wrong_version_cannot_emit_artifacts(self):
        self.package_fixture(version="6")
        result = self.run_script("package_release.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unexpected release identity or version", result.stderr)
        self.assertFalse((self.root / "app/build/outputs/lattice").exists())

    def test_unprotected_branch_cannot_package_release(self):
        self.package_fixture()
        self.environment["CI_COMMIT_REF_PROTECTED"] = "false"
        result = self.run_script("package_release.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("protected default branch", result.stderr)


@unittest.skipUnless(os.name == "posix", "Linux CI shell contract tests")
class SdkShellContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "ci").mkdir()
        shutil.copyfile(SCRIPTS / "prepare_sdk.sh", self.root / "ci/prepare_sdk.sh")
        self.sdk = self.root / ".dependencies/sdk"
        self.sdk.mkdir(parents=True)
        subprocess.run(["git", "init", "--quiet", str(self.sdk)], check=True)
        (self.sdk / "LICENSE").write_text("Synthetic fixture license\n")
        subprocess.run(["git", "-C", str(self.sdk), "add", "LICENSE"], check=True)
        subprocess.run(["git", "-C", str(self.sdk), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "Fixture"], check=True)
        revision = subprocess.check_output(["git", "-C", str(self.sdk), "rev-parse", "HEAD"], text=True).strip()
        (self.root / "sdk-revision.txt").write_text(revision + "\n")

    def run_prepare(self):
        return subprocess.run(["sh", str(self.root / "ci/prepare_sdk.sh")], capture_output=True, text=True)

    def test_accepts_clean_exact_pin(self):
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Pinned SDK source and license verified", result.stdout)

    def test_refuses_wrong_revision_without_overwriting(self):
        (self.root / "sdk-revision.txt").write_text("f" * 40)
        result = self.run_prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SDK checkout differs", result.stderr)

    def test_refuses_untracked_source_without_discarding_it(self):
        extra = self.sdk / "preserve.kt"
        extra.write_text("synthetic")
        result = self.run_prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("synthetic", extra.read_text())

    def test_clone_header_works_without_modern_git_config_count(self):
        fixture_sdk = self.root / "fixture-sdk"
        self.sdk.rename(fixture_sdk)
        binary = self.root / "bin"
        binary.mkdir()
        git = binary / "git"
        git.write_text("""#!/bin/sh
set -eu
unset GIT_CONFIG_COUNT
if [ "$1" = clone ]; then
    case "$GIT_CONFIG_PARAMETERS" in "'"*) ;; *) exit 92 ;; esac
    ACTUAL=$("$FIXTURE_REAL_GIT" config --get-urlmatch http.extraHeader https://git.tak.gov/)
    [ "$ACTUAL" = "$FIXTURE_EXPECTED_HEADER" ] || exit 90
    exec "$FIXTURE_REAL_GIT" clone --quiet --no-checkout "$FIXTURE_SDK" "$5"
fi
exec "$FIXTURE_REAL_GIT" "$@"
""")
        git.chmod(0o700)
        environment = os.environ.copy()
        encoded = base64.b64encode(b"gitlab-ci-token:synthetic-token").decode()
        environment.update({
            "PATH": str(binary) + os.pathsep + os.environ["PATH"],
            "FIXTURE_REAL_GIT": shutil.which("git"), "FIXTURE_SDK": str(fixture_sdk),
            "FIXTURE_EXPECTED_HEADER": "Authorization: Basic " + encoded,
            "CI_JOB_TOKEN": "synthetic-token",
            "GIT_CONFIG_PARAMETERS": "",
        })
        result = subprocess.run(["sh", str(self.root / "ci/prepare_sdk.sh")],
                                env=environment, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        config = (self.sdk / ".git/config").read_text()
        self.assertNotIn("synthetic-token", config + result.stdout + result.stderr)
        self.assertNotIn(encoded, config + result.stdout + result.stderr)
