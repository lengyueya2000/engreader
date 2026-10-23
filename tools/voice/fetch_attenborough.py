"""Put the Attenborough cloning voice's model files where the build expects them.

They are 186 MB together, and one of them — `decoder.onnx`, 124.7 MB — is past the 100 MB
ceiling a public git host will accept, so they are deliberately not in the repository.
They ship as an asset on the project's GitHub Release instead, and this script is the one
step between a fresh clone and a build that contains the voice:

    python tools/voice/fetch_attenborough.py
    python tools/voice/fetch_attenborough.py --from ~/Downloads/engreader-attenborough-model.zip

Every file is checked against the SHA-256 it had when the voice was measured, so a
truncated download, or an asset silently replaced with a different model, fails here
rather than on a phone. A different model is worth refusing loudly: ZipVoice will happily
clone whichever voice the prompt carries, so a swapped decoder produces a voice that is
simply someone else — and nothing in the app can tell that from a successful load.

Before this has run, the app still builds and still speaks: the cloning voice fails to
load and the reader silently falls back to the system voice, which is why
`VoiceReferenceAssetsTest` fails in that state and names the files it is missing.

The reference clip and its text (`reference.wav`, `reference.txt`) are small and are in
the repository: they are the part of this voice that is an editorial choice rather than a
download. See the README's "参考音频" for how that clip was cut and why.
"""
import argparse
import hashlib
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

# The asset is named without a version because the model is immutable: the same file
# answers for every release of the app. The tag in the URL is only where it was first
# published.
DEFAULT_URL = (
    "https://github.com/lengyueya2000/engreader/releases/download/"
    "v1.8.0/engreader-attenborough-model.zip"
)

# name -> (bytes, sha256), measured on the assets the 1.8.0 release was built from.
# `tools/voice/measure_bandwidth.py` and the ECAPA/WER runs in the README's voice notes
# were made against exactly these bytes.
FILES = {
    "decoder.onnx": (
        124657100,
        "3CC2E08A96610D7EA1B227398E97CDBBE0414499741D3AEC0B8113DB2A2AB251",
    ),
    "encoder.onnx": (
        5570211,
        "F2DE9A761A85E5DDD125DEE6E05BAD1C7EE92C11B83B4D775DAB216A6AA41379",
    ),
    "vocoder.onnx": (
        54157409,
        "BCB3B970E384161C4D634F0BB9E999FF1C471B34C9BC0B1049A5014065ED3CC0",
    ),
    "lexicon.txt": (
        1727147,
        "C94B049D970E971ACDC2A7E5E7B23CA594222A9D640E417C711F2AD1E7BB7D46",
    ),
    "tokens.txt": (
        2570,
        "CE98C1AFC5F7A20C2484DFFDD68A1FFF0A4A2CC707328833750C4476C37CDBDA",
    ),
}

DEST = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "tts" / "en_GB-attenborough"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def download(url, into):
    print(f"downloading {url}")
    target = Path(into) / "attenborough-model.zip"
    with urllib.request.urlopen(url) as response, open(target, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        while True:
            block = response.read(1 << 20)
            if not block:
                break
            out.write(block)
            done += len(block)
            if total:
                print(f"\r  {done / 1e6:7.1f} / {total / 1e6:.1f} MB", end="", flush=True)
        print()
    return target


def install(archive, dest):
    """Unpacks into a staging directory, verifies everything, then moves it into place.

    All-or-nothing on purpose: a directory holding a *mixture* of the old and the new
    model is the one state nothing downstream can detect, so a refused download has to
    leave the assets exactly as it found them. The staging directory lives inside the
    destination so the final move is a rename within one filesystem rather than a copy.
    """
    failures = []
    dest.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".incoming-", dir=dest))
    try:
        with zipfile.ZipFile(archive) as bundle:
            entries = {
                Path(name).name: name
                for name in bundle.namelist()
                if not name.endswith("/")
            }
            for name, (size, digest) in FILES.items():
                entry = entries.get(name)
                if entry is None:
                    failures.append(f"{name}: not in the archive")
                    continue
                target = staging / name
                with bundle.open(entry) as source, open(target, "wb") as out:
                    shutil.copyfileobj(source, out)
                actual_size = target.stat().st_size
                actual = sha256(target)
                if actual_size != size or actual != digest:
                    failures.append(
                        f"{name}: expected {size} bytes / {digest}, "
                        f"got {actual_size} bytes / {actual}"
                    )
                else:
                    print(f"  ok {name} ({actual_size / 1e6:.1f} MB)")
        if failures:
            return failures
        for name in FILES:
            os.replace(staging / name, dest / name)
        return []
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default=DEFAULT_URL, help="release asset to download")
    parser.add_argument("--from", dest="local", help="a zip already on disk, instead of downloading")
    parser.add_argument("--dest", default=str(DEST), help="where the files belong")
    args = parser.parse_args()

    dest = Path(args.dest)
    print(f"target: {dest}")

    if args.local:
        archive = Path(args.local)
        if not archive.is_file():
            print(f"!! no such file: {archive}")
            return 2
        keep = None
    else:
        keep = Path(tempfile.mkdtemp(prefix="attenborough-"))
        try:
            archive = download(args.url, keep)
        except Exception as error:  # noqa: BLE001 - the message is the whole point
            print(f"!! download failed: {error}")
            print("   the model is an asset on the GitHub Release; see the README's 构建 一节")
            return 2

    try:
        failures = install(archive, dest)
    except zipfile.BadZipFile:
        print("!! that is not a zip archive (a partial download?)")
        return 2
    finally:
        if keep is not None:
            shutil.rmtree(keep, ignore_errors=True)

    if failures:
        print("\n!! refused, the model does not match the one the voice was measured with:")
        for line in failures:
            print(f"   {line}")
        print("\nNothing was left half-installed on purpose: a mismatched decoder clones a")
        print("different voice and the app cannot tell. Re-download and try again.")
        return 1

    print(f"\nall {len(FILES)} files verified. Next: ./gradlew :app:assembleRelease")
    return 0


if __name__ == "__main__":
    sys.exit(main())