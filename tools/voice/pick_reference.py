"""Pick and verify the reference clip a cloning voice copies.

ZipVoice is zero-shot: the voice is not a speaker inside a trained graph, it is a
recording handed to the engine at synthesis time. That makes this script part of the
voice's definition rather than a one-off — re-running it is how the clip gets
replaced, and the checks below are why the shipped clip can be trusted.

    pip install faster-whisper pyarrow numpy
    python pick_reference.py --dataset <attenborough parquet> --out <dir>

## Why the checks are here

The engine aligns the audio against the text that accompanies it. Hand it a text that
does not match and it still speaks, but the voice drifts — a failure that sounds like
"a bit off" rather than like a bug, which is exactly the kind of thing that survives
review. So the text is taken from a transcription of the audio, and the transcription
is only accepted when it agrees with the dataset's own text.

Two more constraints, both of which bite silently:

  - ZipVoice truncates a prompt past its limit. A truncated prompt no longer matches
    its text, so the clip is kept well inside that limit rather than at it.
  - The cut has to land on a word boundary. Half a word of audio against a whole word
    of text is the same misalignment by another route.

## What is measured

The chosen clip is reported with the numbers that decide whether it is usable: its
duration, its level (a silent clip still parses, and the clone would have nothing to
copy), and the agreement between the dataset text and the transcription.

The same measurements are what `measure.py` reports for the corpus voices, so a
replacement clip can be compared against the shipped one on equal terms.
"""

import argparse
import os
import struct
import sys
import wave
from collections import Counter

import numpy as np


# ------------------------------------------------------------------ wav helpers

def decode_wav(blob):
    """One RIFF/WAVE payload to mono float32, or None when it is a shape we skip.

    Deliberately narrow: the datasets here hold 16-bit PCM, and a file in some other
    width would be decoded into noise rather than into an error.
    """
    if len(blob) < 12 or blob[:4] != b"RIFF" or blob[8:12] != b"WAVE":
        return None
    pos, fmt, data = 12, None, None
    while pos + 8 <= len(blob):
        cid = blob[pos:pos + 4]
        size = struct.unpack("<I", blob[pos + 4:pos + 8])[0]
        body = pos + 8
        if body + size > len(blob):
            return None
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", blob[body:body + 16])
        elif cid == b"data":
            data = blob[body:body + size]
        pos = body + size + (size & 1)
    if fmt is None or data is None:
        return None
    tag, channels, rate, _, _, bits = fmt
    if tag == 3 and bits == 32:
        samples = np.frombuffer(data, dtype=np.float32)
    elif tag == 1 and bits == 16:
        samples = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    else:
        return None
    if channels == 2:
        samples = samples.reshape(-1, 2).mean(axis=1)
    elif channels != 1:
        return None
    return np.ascontiguousarray(samples, dtype=np.float32), rate


def write_wav(path, samples, rate):
    pcm = np.clip(np.asarray(samples, dtype=np.float32), -1.0, 1.0)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((pcm * 32767.0).astype(np.int16).tobytes())


# ------------------------------------------------------------------- comparison

def normalise(text):
    """Letters, digits and single spaces — punctuation carries no alignment weight."""
    return " ".join("".join(c.lower() if c.isalnum() or c.isspace() else " "
                            for c in text).split())


def similarity(a, b):
    """Word-level F1, which tolerates a length difference that edit distance punishes."""
    wa, wb = normalise(a).split(), normalise(b).split()
    if not wa or not wb:
        return 0.0
    overlap = sum((Counter(wa) & Counter(wb)).values())
    return 2 * overlap / (len(wa) + len(wb))


def transcribe(asr, samples, rate):
    segments, _ = asr.transcribe(samples, language="en", beam_size=5,
                                 vad_filter=False)
    return " ".join(s.text.strip() for s in segments).strip()


# ------------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", required=True,
                    help="parquet with `audio` (embedded WAV bytes) and `text` columns")
    ap.add_argument("--out", required=True,
                    help="directory to write reference.wav and reference.txt into")
    ap.add_argument("--min-seconds", type=float, default=5.0)
    ap.add_argument("--max-seconds", type=float, default=9.0,
                    help="kept well inside ZipVoice's prompt limit; see the docstring")
    ap.add_argument("--min-words", type=int, default=12)
    ap.add_argument("--max-words", type=int, default=40)
    ap.add_argument("--min-agreement", type=float, default=0.95,
                    help="dataset text vs transcription; below this the pair is unusable")
    ap.add_argument("--asr", default="small", help="faster-whisper model size")
    ap.add_argument("--top", type=int, default=10, help="how many candidates to print")
    args = ap.parse_args()

    try:
        import pyarrow.parquet as pq
    except ImportError:
        sys.exit("pip install pyarrow")
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        sys.exit("pip install faster-whisper")

    table = pq.read_table(args.dataset)
    if "audio" not in table.column_names or "text" not in table.column_names:
        sys.exit(f"expected `audio` and `text` columns, got {table.column_names}")
    audios = table.column("audio").to_pylist()
    texts = table.column("text").to_pylist()
    print(f"{table.num_rows} rows in {args.dataset}", file=sys.stderr)

    asr = WhisperModel(args.asr, device="cpu", compute_type="int8")

    scored = []
    for index, (audio, text) in enumerate(zip(audios, texts)):
        blob = audio.get("bytes") if isinstance(audio, dict) else None
        if not blob:
            continue
        decoded = decode_wav(blob)
        if decoded is None:
            continue
        samples, rate = decoded
        seconds = len(samples) / rate
        words = len(text.split())
        if not (args.min_seconds <= seconds <= args.max_seconds):
            continue
        if not (args.min_words <= words <= args.max_words):
            continue
        # A clip that ends mid-sentence would hand the engine a text with no ending,
        # and a bracketed stage direction is not narration at all.
        if not text.strip().endswith((".", "!", "?")):
            continue
        if any(c in text for c in "[](){}") or not all(ord(c) < 128 for c in text):
            continue

        heard = transcribe(asr, samples, rate)
        agreement = similarity(text, heard)
        scored.append(dict(agreement=agreement, index=index, seconds=seconds,
                           words=words, text=text, heard=heard,
                           samples=samples, rate=rate))

    if not scored:
        sys.exit("no candidate matched the length and text filters")

    scored.sort(key=lambda row: (-row["agreement"], -row["words"]))
    print(f"\n{len(scored)} candidates passed the filters; best {args.top}:\n")
    for row in scored[:args.top]:
        print(f"  agreement {row['agreement']:.3f}  idx {row['index']:>3}  "
              f"{row['seconds']:.2f}s  {row['words']:>2}w")
        print(f"    dataset : {row['text'][:96]}")
        print(f"    heard   : {row['heard'][:96]}")

    best = scored[0]
    if best["agreement"] < args.min_agreement:
        sys.exit(f"\nbest agreement {best['agreement']:.3f} is below "
                 f"{args.min_agreement}; no trustworthy reference in this dataset")

    os.makedirs(args.out, exist_ok=True)
    wav_path = os.path.join(args.out, "reference.wav")
    txt_path = os.path.join(args.out, "reference.txt")
    write_wav(wav_path, best["samples"], best["rate"])
    # The transcription, not the dataset text: it is what the audio actually says, and
    # the engine aligns against exactly this string.
    with open(txt_path, "w", encoding="utf-8") as handle:
        handle.write(best["heard"] + "\n")

    samples = best["samples"]
    peak = float(np.abs(samples).max())
    rms = float(np.sqrt((samples ** 2).mean()))
    print(f"\nchosen idx {best['index']}  agreement {best['agreement']:.3f}")
    print(f"  {best['seconds']:.2f}s @ {best['rate']}Hz  "
          f"{len(samples)} samples  peak {peak:.3f}  rms {rms:.4f}")
    print(f"  wrote {wav_path}")
    print(f"  wrote {txt_path}")
    print(f"  text: {best['heard']}")

    if rms < 0.01:
        print("\nWARNING: the clip is nearly silent; the clone would have little to copy.",
              file=sys.stderr)
    if peak > 0.999:
        print("\nWARNING: the clip is clipped; the clone would learn a distortion.",
              file=sys.stderr)


if __name__ == "__main__":
    main()
