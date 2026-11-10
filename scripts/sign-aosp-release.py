#!/usr/bin/env python3
"""Sign the compiled ARM64 release with the public AOSP testkey and verify it."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
CERT_SHA256 = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"


def run(*args: object) -> str:
    return subprocess.run(
        [str(arg) for arg in args], cwd=ROOT, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    ).stdout


def main() -> None:
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    keys = ROOT / ".github/keys"
    output = ROOT / "build/aosp-release"
    output.mkdir(parents=True, exist_ok=True)
    config = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName = "([^"]+)"', config).group(1)
    version_code = int(re.search(r"versionCode = (\d+)", config).group(1))

    metadata_path = ROOT / "app/build/outputs/apk/release/output-metadata.json"
    if not metadata_path.is_file():
        locator = ROOT / "app/build/intermediates/apk_ide_redirect_file/release/createReleaseApkListingFileRedirect/redirect.txt"
        listing = [line.removeprefix("listingFile=") for line in locator.read_text().splitlines()
                   if line.startswith("listingFile=")]
        if len(listing) != 1:
            raise ValueError("Cannot resolve release APK metadata")
        metadata_path = (locator.parent / listing[0]).resolve()
    if not metadata_path.resolve().is_relative_to(ROOT / "app/build"):
        raise ValueError("APK metadata is outside the build directory")
    metadata = json.loads(metadata_path.read_text())
    if metadata["applicationId"] != "com.cyletix.cyime" or metadata["variantName"] != "release":
        raise ValueError("Unexpected release identity")
    elements = [entry for entry in metadata["elements"] if
                {"filterType": "ABI", "value": "arm64-v8a"} in entry["filters"]]
    if len(elements) != 1:
        raise ValueError("Expected exactly one ARM64 release APK")
    entry = elements[0]
    if entry["versionName"] != version or entry["versionCode"] != version_code:
        raise ValueError("Release version does not match the source")
    source = (metadata_path.parent / entry["outputFile"]).resolve()
    if source.parent != metadata_path.parent.resolve():
        raise ValueError("APK is outside the metadata directory")
    signed = output / f"CyIME-{version}-testkey-arm64-v8a.apk"
    with tempfile.TemporaryDirectory(prefix="cyime-sign-") as temporary:
        aligned = Path(temporary) / "aligned.apk"
        run(build_tools / "zipalign", "-P", "16", "-f", "4", source, aligned)
        run(build_tools / "apksigner", "sign",
            "--key", keys / "aosp-testkey.pk8", "--cert", keys / "aosp-testkey.x509.pem",
            "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true", "--out", signed, aligned)
    verification = run(build_tools / "apksigner", "verify", "--verbose", "--print-certs",
                       "--min-sdk-version", "23", signed)
    for scheme in ("v1 scheme (JAR signing)", "v2 scheme (APK Signature Scheme v2)",
                   "v3 scheme (APK Signature Scheme v3)"):
        if f"Verified using {scheme}: true" not in verification:
            raise ValueError(f"Signature verification failed: {scheme}")
    if f"certificate SHA-256 digest: {CERT_SHA256}" not in verification:
        raise ValueError("APK is not signed with the standard AOSP testkey")
    (output / "signature-verification.txt").write_text(verification, encoding="utf-8")
    run(build_tools / "zipalign", "-c", "-P", "16", "4", signed)
    badging = run(build_tools / "aapt", "dump", "badging", signed)
    expected = f"package: name='com.cyletix.cyime' versionCode='{version_code}' versionName='{version}'"
    if expected not in badging or "application-debuggable" in badging or "native-code: 'arm64-v8a'" not in badging:
        raise ValueError("Unexpected APK package, version, ABI or debuggable flag")
    (output / "apk-badging.txt").write_text(badging, encoding="utf-8")
    with signed.open("rb") as stream:
        sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    (output / "SHA256SUMS").write_text(f"{sha256}  {signed.name}\n", encoding="utf-8")
    receipt = {
        "source_commit": run("git", "rev-parse", "HEAD").strip(),
        "version_name": version, "version_code": version_code,
        "application_id": "com.cyletix.cyime", "abi": "arm64-v8a", "build_type": "release",
        "signing_certificate_sha256": CERT_SHA256, "apk": signed.name,
        "bytes": signed.stat().st_size, "sha256": sha256,
        "run_url": f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    }
    (output / "release-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
        summary.write(f"### CyIME {version} AOSP-signed release\n\n")
        summary.write(f"- Source: `{receipt['source_commit']}`\n- APK: `{signed.name}`\n- SHA-256: `{sha256}`\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
