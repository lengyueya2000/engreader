"""Fetch the raw inputs `build_dict.py` and `build_seed.py` expect in `tools/raw/`.

Neither builder could be run from a fresh clone. They read `tools/raw/`, which is
git-ignored and was filled by a script that was not in the repository, so the sources
were recorded only as file names and the assets already committed could not be
reproduced or audited. This script is that missing step; `tools/DATA_SOURCES.md` records
where every input comes from and the digest of each committed artifact.

    python tools/fetch_data.py                  # Gutenberg texts + ECDICT lemma list
    python tools/fetch_data.py --ecdict <url>   # also fetch an ECDICT csv
    python tools/fetch_data.py --verify         # check the committed assets' digests

Only the Gutenberg texts and ECDICT's `lemma.en.txt` have stable, guessable URLs. The
ECDICT csv is a release asset whose name changes between releases, so it is passed in
explicitly rather than guessed at.
"""
import hashlib
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")

GUTENBERG = {
    "pg1342.txt": 1342,   # Pride and Prejudice
    "pg1661.txt": 1661,   # The Adventures of Sherlock Holmes
    "pg1228.txt": 1228,   # On the Origin of Species
    "pg84.txt": 84,       # Frankenstein
    "pg345.txt": 345,     # Dracula
    "pg35.txt": 35,       # The Time Machine
}

LEMMA_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/lemma.en.txt"

# Digests of what is committed today; see tools/DATA_SOURCES.md.
COMMITTED = {
    "dict.db": "611176BEF3AB385CD4519EC87E9DBA0B492905E2F23B8681AC2DE5E01CC442E6",
    "seed_articles.json": "A69664862898197AAC2C29E1B8CBB79B8BA06DAB3FC5661FA3993CC65DEAC6F6",
}

UA = {"User-Agent": "EngReader-data-fetch/1.0"}


def download(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        print("  keep %s (%d bytes)" % (os.path.basename(path), os.path.getsize(path)))
        return
    print("  get  %s" % url)
    request = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    with open(path, "wb") as handle:
        handle.write(data)
    print("  ->   %s (%d bytes)" % (os.path.basename(path), len(data)))


def digest(path):
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            sha.update(block)
    return sha.hexdigest().upper()


def verify():
    """Check the committed assets against the recorded digests."""
    ok = True
    for name, expected in sorted(COMMITTED.items()):
        path = os.path.join(ASSETS, name)
        if not os.path.exists(path):
            print("MISSING %s" % name)
            ok = False
            continue
        actual = digest(path)
        state = "ok  " if actual == expected else "DIFF"
        if actual != expected:
            ok = False
        print("%s %s\n     expected %s\n     actual   %s" % (state, name, expected, actual))
    return ok


def main():
    if "--verify" in sys.argv:
        raise SystemExit(0 if verify() else 1)

    os.makedirs(RAW, exist_ok=True)
    for name, ebook in sorted(GUTENBERG.items()):
        url = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt" % (ebook, ebook)
        download(url, os.path.join(RAW, name))
    download(LEMMA_URL, os.path.join(RAW, "lemma.en.txt"))

    if "--ecdict" in sys.argv:
        index = sys.argv.index("--ecdict")
        url = sys.argv[index + 1] if index + 1 < len(sys.argv) else ""
        if not url.startswith("http"):
            raise SystemExit("--ecdict needs the csv URL as its argument")
        download(url, os.path.join(RAW, "ecdict.csv"))
    else:
        print("\nnote: ecdict.csv is not fetched. Pass --ecdict <url> with the csv from")
        print("      https://github.com/skywind3000/ECDICT/releases (see DATA_SOURCES.md).")

    print("\nnext: python tools/build_dict.py && python tools/build_seed.py")
    print("      python tools/build_translations.py")
    print("      python tools/fetch_data.py --verify")


if __name__ == "__main__":
    main()