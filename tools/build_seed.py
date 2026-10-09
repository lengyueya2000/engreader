"""Generate the bundled offline reading pack.

Takes public-domain Project Gutenberg texts (downloaded into tools/raw/) and emits
`app/src/main/assets/seed_articles.json`: a handful of graded passages so the app
has something to read on first launch and with no network.

Public domain sources, all from Project Gutenberg:
  Pride and Prejudice (1342), The Adventures of Sherlock Holmes (1661),
  On the Origin of Species (1228), Frankenstein (84), Dracula (345),
  The Time Machine (35)
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "seed_articles.json")

# (file, title, author, difficulty 1..5, which chapter marker to start from, target words)
PASSAGES = [
    ("pg1661.txt", "A Scandal in Bohemia", "Arthur Conan Doyle", 2,
     "To Sherlock Holmes she is always the woman.", 420),
    ("pg1342.txt", "Pride and Prejudice — Chapter I", "Jane Austen", 2,
     "It is a truth universally acknowledged", 460),
    ("pg345.txt", "Dracula — Jonathan Harker's Journal", "Bram Stoker", 3,
     "3 May. Bistritz.", 430),
    ("pg84.txt", "Frankenstein — Letter I", "Mary Shelley", 3,
     "You will rejoice to hear", 440),
    ("pg35.txt", "The Time Machine — Chapter I", "H. G. Wells", 3,
     "The Time Traveller (for so it will be convenient to speak of him)", 430),
    ("pg1228.txt", "On the Origin of Species — Introduction", "Charles Darwin", 5,
     "When on board H.M.S.", 430),
    ("pg1661.txt", "The Science of Deduction", "Arthur Conan Doyle", 4,
     "You see, but you do not observe", 420),
    ("pg1342.txt", "Pride and Prejudice — The Assembly", "Jane Austen", 4,
     "Mr. Bingley was good-looking and gentlemanlike", 420),
]

# Gutenberg wraps every line at ~70 columns. Paragraphs are separated by blank
# lines, so every remaining single newline inside a block is a wrap and can be
# joined; the earlier version only joined lines starting lowercase, which left
# "Netherfield Park is let at last?" and every other capitalised continuation as
# its own paragraph in the reader.
WRAP = re.compile(r"\s*\n\s*")


def clean(text):
    start = text.find("*** START")
    if start != -1:
        text = text[text.find("\n", start) + 1:]
    end = text.find("*** END")
    if end != -1:
        text = text[:end]
    text = text.replace("_", "")
    return text


def reflow(block):
    """Undo hard line wrapping while keeping real paragraph breaks."""
    paras = re.split(r"\n\s*\n", block)
    out = []
    for p in paras:
        p = WRAP.sub(" ", p.strip())
        p = re.sub(r"[ \t]+", " ", p)
        out.append(p.strip())
    return [p for p in out if p]


def take_passage(text, anchor, target_words):
    at = text.find(anchor)
    if at < 0:
        # Anchor may be split across a line wrap; try a looser match.
        loose = re.sub(r"\s+", " ", anchor)
        flat = re.sub(r"\s+", " ", text)
        at_flat = flat.find(loose)
        if at_flat < 0:
            return None
        # Map back to the original by counting words.
        skipped = flat[:at_flat].count(" ")
        idx = 0
        for _ in range(skipped):
            idx = text.find(" ", idx) + 1
        at = idx
    block = text[at:at + target_words * 7]
    paras = reflow(block)
    kept, total = [], 0
    for p in paras:
        words = len(p.split())
        if total + words > target_words and kept:
            break
        kept.append(p)
        total += words
    return kept


def merge_short(paragraphs, min_words=45):
    """Gutenberg texts arrive as many one-line paragraphs; merge runs of them so the
    reader does not render a wall of single sentences."""
    merged, buffer = [], ""
    for p in paragraphs:
        buffer = f"{buffer} {p}".strip() if buffer else p
        if len(buffer.split()) >= min_words:
            merged.append(buffer)
            buffer = ""
    if buffer:
        merged.append(buffer)
    return merged


def split_long(paragraphs, max_words=115):
    """Cap paragraph length so each screen holds a readable unit of prose."""
    out = []
    for p in paragraphs:
        if len(p.split()) <= max_words:
            out.append(p)
            continue
        sentences = re.split(r"(?<=[.!?\u201d\"]) +", p)
        buffer = ""
        for s in sentences:
            candidate = f"{buffer} {s}".strip() if buffer else s
            if len(candidate.split()) > max_words and buffer:
                out.append(buffer)
                buffer = s
            else:
                buffer = candidate
        if buffer:
            out.append(buffer)
    return out


def split_for_reader(paragraphs):
    """Normalise the passage so it reads as continuous prose on a phone."""
    if not paragraphs:
        return paragraphs
    paragraphs = merge_short(paragraphs)

    first = paragraphs[0]
    if not first[:1].isupper():
        # Starts mid-sentence because the anchor landed inside one: cut to the first
        # sentence boundary so the passage opens cleanly.
        dot = first.find(". ")
        if 0 <= dot < 200:
            paragraphs = [first[dot + 2:].strip()] + paragraphs[1:]

    paragraphs = [p for p in paragraphs if len(p.split()) >= 20]
    return split_long(paragraphs)


def main():
    articles = []
    for filename, title, author, difficulty, anchor, target in PASSAGES:
        path = os.path.join(RAW, filename)
        if not os.path.exists(path):
            print(f"  skip {title}: missing {filename}", file=sys.stderr)
            continue
        text = clean(open(path, encoding="utf-8", errors="replace").read())
        paras = take_passage(text, anchor, target)
        if not paras:
            print(f"  skip {title}: anchor not found", file=sys.stderr)
            continue
        paras = split_for_reader(paras)
        body = "\n\n".join(paras)
        if len(body.split()) < 200:
            print(f"  skip {title}: only {len(body.split())} words", file=sys.stderr)
            continue
        articles.append({
            "sourceId": "gutenberg",
            "title": title,
            "subtitle": f"{author} · Project Gutenberg 公版文本",
            "author": author,
            "url": f"gutenberg://{filename}/{anchor[:40]}",
            "difficulty": difficulty,
            "body": body,
        })
        print(f"  {title}: {len(body.split())} words, {len(paras)} paragraphs")

    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "articles": articles}, f, ensure_ascii=False, indent=1)
    print(f"wrote {len(articles)} articles -> {OUT} ({os.path.getsize(OUT)/1024:.0f} KB)")


if __name__ == "__main__":
    main()
