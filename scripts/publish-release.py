#!/usr/bin/env python3
"""Direct release upload with Actions disabled, immutable assets and GET/hash verification.

Requires an existing source tag and a canonically signed APK. Run only after
the model redistribution gate and canonical key backup have been satisfied.
No CI or Actions endpoints are used to execute work.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def api(path):
    return json.loads(run("gh", "api", path))


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--build-manifest", type=Path, required=True)
    parser.add_argument("--cert-sha256", required=True)
    parser.add_argument("--model-distribution-reviewed", action="store_true", required=True)
    parser.add_argument("--canonical-key-backup-confirmed", action="store_true", required=True)
    args = parser.parse_args()
    if not args.apk.is_file() or not args.notes.is_file():
        raise SystemExit("APK/notes missing")
    manifest = json.loads(args.build_manifest.read_text())
    if manifest.get("sourceCommit") != args.commit or manifest.get("apk", {}).get("sha256") != digest(args.apk):
        raise SystemExit("Build manifest does not bind this source commit to these exact APK bytes")
    project = Path(__file__).resolve().parent.parent
    run("python3", str(project / "scripts/verify-artifact.py"), str(args.apk), "--mode", "release",
        "--sdk", os.environ["ANDROID_HOME"], "--expected-certificate-sha256", args.cert_sha256,
        "--model-manifest", str(project / "MODEL_MANIFEST.json"))
    if api(f"repos/{args.repo}/actions/permissions").get("enabled") is not False:
        raise SystemExit("Actions are not confirmed disabled; no remote mutation was made")
    repo = api(f"repos/{args.repo}")
    ref = api(f"repos/{args.repo}/git/ref/tags/{args.tag}")["object"]
    while ref["type"] == "tag":
        ref = api(f"repos/{args.repo}/git/tags/{ref['sha']}")["object"]
    if ref["type"] != "commit" or ref["sha"] != args.commit:
        raise SystemExit("Existing source tag does not identify the validated commit")
    apksigner = str(Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner")
    verification = run(apksigner, "verify", "--verbose", "--print-certs", str(args.apk))
    certificate = next((line.split(": ", 1)[1] for line in verification.splitlines()
                        if line.startswith("Signer #1 certificate SHA-256 digest: ")), "")
    normalize = lambda value: value.lower().replace(":", "").replace(" ", "")
    if len(normalize(args.cert_sha256)) != 64 or normalize(certificate) != normalize(args.cert_sha256):
        raise SystemExit("Canonical certificate mismatch")
    # Fail on an existing release. Never use --clobber and never mutate an old URL.
    releases = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{args.repo}/releases"))
    if any(release["tag_name"] == args.tag for page in releases for release in page):
        raise SystemExit("Release already exists; choose a new validated tag")
    run("gh", "release", "create", args.tag, str(args.apk), "--repo", args.repo,
        "--verify-tag", "--prerelease", "--title", f"MotionPin {args.tag} — device validation pending",
        "--notes-file", str(args.notes))
    release = api(f"repos/{args.repo}/releases/tags/{args.tag}")
    asset = next(asset for asset in release["assets"] if asset["name"] == args.apk.name)
    if asset["state"] != "uploaded" or asset["size"] != args.apk.stat().st_size:
        raise SystemExit("Uploaded asset state/size mismatch")
    with tempfile.TemporaryDirectory() as directory:
        received = Path(directory) / args.apk.name
        if repo["private"]:
            # gh uses the existing identity and downloads the real asset bytes.
            run("gh", "release", "download", args.tag, "--repo", args.repo,
                "--pattern", args.apk.name, "--dir", directory)
        else:
            run("curl", "--fail", "--location", "--silent", "--show-error",
                asset["browser_download_url"], "--output", str(received))
        if received.read_bytes()[:4] != b"PK\x03\x04" or digest(received) != digest(args.apk):
            raise SystemExit("Downloaded bytes are not the signed original APK")
    print(json.dumps({"release_url": release["html_url"],
                      "apk_url": asset["browser_download_url"],
                      "authentication_required": repo["private"],
                      "sha256": digest(args.apk), "size": asset["size"],
                      "download_hash_verified": True}, indent=2))


if __name__ == "__main__":
    main()
