# Raw inputs and provenance

`build_dict.py` and `build_seed.py` read from `tools/raw/`, which is git-ignored and was
never reproducible: the script that filled it was not in the repository, and the sources
were recorded only as file names. `tools/fetch_data.py` now fetches what can be fetched
and lists what has to be placed by hand.

## Inputs

| File | Consumed by | Source |
| --- | --- | --- |
| `ecdict.csv` | `build_dict.py` | ECDICT (`skywind3000/ECDICT`) — the CSV release asset. The exact snapshot the committed `dict.db` was built from was **not recorded**; see "Limits" below. |
| `lemma.en.txt` | `build_dict.py` | `skywind3000/ECDICT` repository root (`lemma.en.txt`), or the same release bundle. |
| `pg1342.txt` | `build_seed.py` | Project Gutenberg ebook 1342, *Pride and Prejudice* |
| `pg1661.txt` | `build_seed.py` | Project Gutenberg ebook 1661, *The Adventures of Sherlock Holmes* |
| `pg1228.txt` | `build_seed.py` | Project Gutenberg ebook 1228, *On the Origin of Species* |
| `pg84.txt` | `build_seed.py` | Project Gutenberg ebook 84, *Frankenstein* |
| `pg345.txt` | `build_seed.py` | Project Gutenberg ebook 345, *Dracula* |
| `pg35.txt` | `build_seed.py` | Project Gutenberg ebook 35, *The Time Machine* |

All six Gutenberg texts are public domain.

## Committed artifacts

Digests of the assets that ship inside the APK, so a rebuild can at least be compared
with what is committed — `python tools/fetch_data.py --verify` checks them:

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `app/src/main/assets/dict.db` | 15089664 | `611176BEF3AB385CD4519EC87E9DBA0B492905E2F23B8681AC2DE5E01CC442E6` |
| `app/src/main/assets/seed_articles.json` | 39288 | `A69664862898197AAC2C29E1B8CBB79B8BA06DAB3FC5661FA3993CC65DEAC6F6` |

`seed_articles.json` is rewritten by `build_translations.py` after `build_seed.py`, so its
digest changes whenever the translations are regenerated. Nothing in the app depends on a
particular digest; the table is for auditing the committed file, not for enforcing.

## Limits

The ECDICT snapshot behind the committed `dict.db` is not pinned. ECDICT is updated in
place, so re-running `build_dict.py` today produces a *different* database — which is
exactly why this file exists: a rebuild is now a documented, repeatable procedure whose
result can be diffed against the committed digest, instead of an unexplained artifact.
Pinning the snapshot would need the upstream file's digest, and that was not recorded at
the time the asset was built; the recorded *output* digest is what can still be checked.