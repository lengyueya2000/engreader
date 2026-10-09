"""Generate Chinese translations for the bundled offline reading pack.

The offline pack is the one place where the app promises Chinese with no network at
all, so its translations are produced here, at build time, and shipped inside
`seed_articles.json`. Everything else is translated on demand by the app.

Run this after `build_seed.py`; it rewrites the same file in place, adding a
`translation` array per article, aligned with the `body` paragraphs.

    python tools/build_translations.py            # translate anything missing
    python tools/build_translations.py --force    # redo everything
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "..", "app", "src", "main", "assets", "seed_articles.json")

# Must match `Paragraphs.split` in the app: the reader re-splits the stored body,
# and a translation list that does not line up with *its* paragraph count would be
# silently discarded on load.
PARAGRAPH_SPLIT = re.compile(r"\n\s*\n|\n")


def paragraphs_of(body):
    return [p.strip() for p in PARAGRAPH_SPLIT.split(body) if p.strip()]

UA = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                  "Chrome/120.0 Mobile Safari/537.36 EngReader/1.0",
    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
}

BATCH_URL = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=zh-CN"
SINGLE_URL = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh-CN&dt=t"
MYMEMORY_URL = "https://api.mymemory.translated.net/get"


def post(url, form, timeout=30):
    req = urllib.request.Request(url, data=form.encode("utf-8"), headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode("utf-8")


def google_batch(paragraphs):
    """One request for the whole article: the endpoint takes repeated `q` params."""
    form = "&".join("q=" + urllib.parse.quote(p) for p in paragraphs)
    out = json.loads(post(BATCH_URL, form))
    if not isinstance(out, list) or len(out) != len(paragraphs):
        raise ValueError("batch returned %s items, expected %d" % (
            len(out) if isinstance(out, list) else type(out).__name__, len(paragraphs)))
    return [str(s) for s in out]


def google_single(paragraph):
    body = json.loads(post(SINGLE_URL, "q=" + urllib.parse.quote(paragraph)))
    segments = body[0]
    return "".join(seg[0] for seg in segments if seg and seg[0])


def mymemory(paragraph):
    """Fallback: 500-char cap per request, so split on sentence ends."""
    pieces = []
    buffer = ""
    for sentence in paragraph.replace("\n", " ").split(". "):
        candidate = (buffer + ". " + sentence).strip(". ") if buffer else sentence
        if len(candidate) > 450 and buffer:
            pieces.append(buffer)
            buffer = sentence
        else:
            buffer = candidate
    if buffer:
        pieces.append(buffer)

    parts = []
    for piece in pieces:
        form = "q=" + urllib.parse.quote(piece) + "&langpair=en%7Czh-CN"
        data = json.loads(post(MYMEMORY_URL, form))
        text = (data.get("responseData") or {}).get("translatedText") or ""
        if not text or "LIMIT EXCEEDED" in text.upper():
            raise ValueError("MyMemory: %s" % (text or "empty response"))
        parts.append(text)
        time.sleep(0.4)
    return "".join(parts)


def translate(paragraphs):
    """Batch first, then per-paragraph Google, then MyMemory."""
    try:
        return google_batch(paragraphs)
    except Exception as e:
        print("    batch failed (%s), falling back per paragraph" % e, file=sys.stderr)

    out = []
    for i, p in enumerate(paragraphs):
        for name, fn in (("google", google_single), ("mymemory", mymemory)):
            try:
                out.append(fn(p))
                break
            except Exception as e:
                last = e
                time.sleep(1.0)
        else:
            raise SystemExit("paragraph %d could not be translated: %s" % (i, last))
        time.sleep(0.3)
    return out


def main():
    force = "--force" in sys.argv
    with open(PACK, encoding="utf-8") as f:
        pack = json.load(f)

    changed = 0
    for article in pack["articles"]:
        paragraphs = [p for p in article["body"].split("\n\n") if p.strip()]
        existing = article.get("translation") or []
        if existing and len(existing) == len(paragraphs) and not force:
            print("  skip %s (already translated)" % article["title"][:40])
            continue

        print("  %s: %d paragraphs, %d chars" % (
            article["title"][:40], len(paragraphs), sum(len(p) for p in paragraphs)))
        article["translation"] = translate(paragraphs)
        changed += 1
        time.sleep(1.0)

    with open(PACK, "w", encoding="utf-8") as f:
        json.dump(pack, f, ensure_ascii=False, indent=1)
    print("updated %d articles -> %s (%.0f KB)" % (changed, PACK, os.path.getsize(PACK) / 1024))


if __name__ == "__main__":
    main()
