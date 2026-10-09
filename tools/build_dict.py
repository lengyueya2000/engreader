"""Build the bundled offline dictionary (dict.db) from ECDICT.

Inputs (downloaded into tools/raw/ by fetch_data.py):
  ecdict.csv    - ECDICT headword table
  lemma.en.txt  - lemma -> inflected forms list

Output: app/src/main/assets/dict.db  (SQLite, read-only at runtime)
"""
import csv, os, re, sqlite3, sys

csv.field_size_limit(10 ** 9)
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "dict.db")

# ECDICT exchange codes: p past, d past participle, i -ing, 3 third person,
# r comparative, t superlative, s plural, 0 lemma, 1 forms-of
EX_CODES = ("p", "d", "i", "3", "r", "t", "s")


def is_headword(w):
    core = w.replace("-", "").replace("'", "").replace(" ", "").replace(".", "")
    return bool(core) and core.isalpha() and w[0].isalpha()


def main():
    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    db = sqlite3.connect(OUT)
    db.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE word (
            word        TEXT PRIMARY KEY COLLATE NOCASE,
            phonetic    TEXT,
            translation TEXT,
            definition  TEXT,
            pos         TEXT,
            collins     INTEGER DEFAULT 0,
            oxford      INTEGER DEFAULT 0,
            tag         TEXT,
            frq         INTEGER DEFAULT 0,
            bnc         INTEGER DEFAULT 0,
            exchange    TEXT
        );
        CREATE TABLE form (form TEXT PRIMARY KEY COLLATE NOCASE, lemma TEXT);
        """
    )

    rows = []
    with open(os.path.join(RAW, "ecdict.csv"), encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            w = (row["word"] or "").strip()
            if not is_headword(w):
                continue
            tr = (row["translation"] or "").strip()
            de = (row["definition"] or "").strip()
            if not tr and not de:
                continue
            # Keep vocabulary that plausibly shows up in real prose. Dropping
            # obscure entries keeps the asset small enough to ship in an APK.
            if not (row["frq"] not in ("", "0") or row["bnc"] not in ("", "0")
                    or row["collins"] not in ("", "0") or row["oxford"] == "1"
                    or (row["tag"] or "").strip()):
                continue

            def num(v):
                try:
                    return int(v)
                except (TypeError, ValueError):
                    return 0

            rows.append((
                w, row["phonetic"] or "", tr, de, row["pos"] or "",
                num(row["collins"]), 1 if row["oxford"] == "1" else 0,
                row["tag"] or "", num(row["frq"]), num(row["bnc"]),
                row["exchange"] or "",
            ))

    db.executemany("INSERT OR IGNORE INTO word VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    headwords = {r[0].lower() for r in rows}

    forms = {}
    # 1) ECDICT exchange column. `0:<lemma>` means "this word is a form of <lemma>";
    # `1:<lemma>` is the reverse. Both give a lemma to fall back on for inflected
    # entries whose own gloss is only "be 的过去式" and carries no frequency data.
    for w, _, _, _, _, _, _, _, _, _, ex in rows:
        if not ex:
            continue
        for part in ex.split("/"):
            if ":" not in part:
                continue
            code, _, val = part.partition(":")
            if code in ("0", "1"):
                lemma = val.strip().lower()
                if lemma and lemma != w.lower():
                    forms.setdefault(w.lower(), lemma)
            elif code in EX_CODES:
                for v in val.split(","):
                    v = v.strip().lower()
                    if v and v != w.lower():
                        forms.setdefault(v, w.lower())
    # 2) lemma.en.txt: "be/4109826 -> is,was,are". The count after the slash is the
    # lemma's corpus frequency, and it is what resolves conflicts: the list also
    # contains junk groups such as "wa -> was" (the Washington abbreviation), and
    # without the count that would displace the correct "be -> was".
    lemma_path = os.path.join(RAW, "lemma.en.txt")
    if os.path.exists(lemma_path):
        best = {}
        with open(lemma_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith(";") or "->" not in line:
                    continue
                left, _, right = line.partition("->")
                head, _, count = left.partition("/")
                lemma = head.strip().lower()
                try:
                    weight = int(count)
                except ValueError:
                    weight = 0
                for v in right.split(","):
                    v = v.strip().lower()
                    if not v or v == lemma:
                        continue
                    if v not in best or weight > best[v][1]:
                        best[v] = (lemma, weight)
        for form, (lemma, _) in best.items():
            forms[form] = lemma

    # A form must not resolve to itself, and the target must exist as an entry.
    forms = {k: v for k, v in forms.items() if k != v and v in headwords}

    db.executemany("INSERT OR IGNORE INTO form VALUES (?,?)", list(forms.items()))
    db.executescript(
        "CREATE INDEX idx_word_frq ON word(frq DESC);"
        "CREATE INDEX idx_word_collins ON word(collins DESC);"
    )
    db.commit()
    n = db.execute("SELECT COUNT(*) FROM word").fetchone()[0]
    nf = db.execute("SELECT COUNT(*) FROM form").fetchone()[0]
    db.execute("VACUUM")
    db.close()
    print(f"words={n} forms={nf} size={os.path.getsize(OUT)/1e6:.1f}MB -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
