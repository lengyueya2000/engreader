"""Measure bundled TTS voices: register, accent and distinctiveness.

This is the tool that picked the British narrator voice. It exists so the choice
can be re-derived rather than trusted, and so the same measurements can rank a
different corpus later.

Run from the directory holding the unpacked models:

    pip install sherpa-onnx numpy
    python measure.py --vctk vits-piper-en_GB-vctk-medium-int8 \\
                      --single en_GB-alan-medium=vits-piper-en_GB-alan-medium-int8 \\
                      --espeak espeak-ng-data

`--single NAME=DIR` may be repeated. `--vctk DIR` expects a Piper multi-speaker
bundle (model, tokens, the .onnx.json with `speaker_id_map`). The espeak data
directory is the one inside any Piper bundle.

What it measures, and why each one matters for a reading voice:

  f0            median fundamental frequency. A male documentary narrator sits
                around 85-105 Hz; above ~130 Hz reads as a bulletin or a woman.
  wpm           words per minute on expository prose. 165-200 is unhurried.
  hnr           harmonics-to-noise ratio. Higher is a clearer, less breathy voice.
  tilt          spectral tilt, dB per octave. Less negative is a brighter voice.
  silence       fraction of the read spent silent, i.e. clause pauses.
  pauses/med    number and median length of silences over 80 ms.
  f0_range      interquartile pitch range in semitones. A narrator moves around.
  rhotic        duration ratio of a minimal pair differing only by post-vocalic
                /r/ ("sore" vs "saw"). Near 1.0 means non-rhotic, i.e. Southern
                British rather than Scottish, Irish or American.

Accent is otherwise taken from the corpus's own speaker sheet, because every
formant-based TRAP-BATH test tried here flipped sign between runs on 200-400 ms
synthesised words. Do not trust one; use the sheet.
"""

import argparse
import json
import os
import re
import sys

import numpy as np

try:
    import sherpa_onnx
except ImportError:
    sys.exit("pip install sherpa-onnx")

PASSAGE = (
    "The ocean covers more than seventy percent of our planet, and yet we have "
    "barely begun to explore it. In the deep water, beyond the reach of sunlight, "
    "animals live that were unknown to science a generation ago. "
    "For most of human history the sea was a boundary rather than a destination. "
    "That changed in the middle of the last century, when a handful of engineers "
    "built machines that could carry a person down into the dark and bring them "
    "back again. What they found there was not the empty basin the textbooks had "
    "promised, but a crowded and improbable world of its own."
)

RHOTIC_PAIRS = [("sore", "saw"), ("pore", "paw"), ("more", "maw"), ("bore", "baw")]


# --------------------------------------------------------------- engine

def build(model, tokens, data_dir, threads=4):
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=model, tokens=tokens, data_dir=data_dir),
            num_threads=threads, debug=False, provider="cpu"),
        max_num_sentences=1, silence_scale=0.2))


def open_bundle(directory, espeak):
    """A Piper bundle: <name>.onnx, tokens.txt, <name>.onnx.json."""
    onnx = [f for f in os.listdir(directory) if f.endswith(".onnx")]
    if not onnx:
        raise SystemExit(f"no .onnx in {directory}")
    model = os.path.join(directory, onnx[0])
    tokens = os.path.join(directory, "tokens.txt")
    meta = json.load(open(os.path.join(directory, onnx[0] + ".json"), encoding="utf-8"))
    return build(model, tokens, espeak), meta


def gen(engine, text, sid):
    audio = engine.generate(text, sid=sid, speed=1.0)
    return np.asarray(audio.samples, dtype=np.float32), audio.sample_rate


# ---------------------------------------------------------------- pitch

def f0_median(x, sr, fmin=60.0, fmax=320.0):
    """Autocorrelation pitch with the half-period error rejected.

    A frame counts only if its period also correlates well at twice and three
    times the lag. Without that check a low male voice reads as its own octave,
    which is what made the first sweep of this corpus report 350 Hz for baritones.
    """
    win, hop = int(0.040 * sr), int(0.010 * sr)
    lo, hi = int(sr / fmax), int(sr / fmin)
    vals = []
    for s in range(0, max(len(x) - win, 0), hop):
        f = x[s:s + win].astype(np.float64)
        f -= f.mean()
        if np.sqrt((f ** 2).mean()) < 1e-3:
            continue
        f *= np.hanning(win)
        ac = np.correlate(f, f, 'full')[win - 1:]
        if ac[0] <= 0:
            continue
        ac = ac / ac[0]
        seg = ac[lo:hi]
        if len(seg) == 0:
            continue
        k = int(np.argmax(seg)) + lo
        if ac[k] < 0.35:
            continue
        if k * 2 < len(ac) and ac[k * 2] > 0.8 * ac[k]:
            k *= 2
        vals.append(sr / k)
    if not vals:
        return None, None, None
    v = np.array(vals)
    return (round(float(np.median(v)), 1),
            round(float(np.percentile(v, 25)), 1),
            round(float(np.percentile(v, 75)), 1))


def f0_range_st(x, sr):
    """Interquartile pitch range in semitones, over voiced frames."""
    win, hop = int(0.040 * sr), int(0.010 * sr)
    lo, hi = int(sr / 320), int(sr / 60)
    vals = []
    for s in range(0, max(len(x) - win, 0), hop):
        f = x[s:s + win].astype(np.float64)
        f -= f.mean()
        if np.sqrt((f ** 2).mean()) < 1e-3:
            continue
        f *= np.hanning(win)
        ac = np.correlate(f, f, 'full')[win - 1:]
        if ac[0] <= 0:
            continue
        ac = ac / ac[0]
        seg = ac[lo:hi]
        if len(seg) == 0:
            continue
        k = int(np.argmax(seg)) + lo
        if ac[k] < 0.40:
            continue
        if k * 2 < len(ac) and ac[k * 2] > 0.8 * ac[k]:
            k *= 2
        vals.append(sr / k)
    if len(vals) < 20:
        return None
    v = np.array(vals)
    return round(float(12 * np.log2(np.percentile(v, 75) / np.percentile(v, 25))), 2)


# -------------------------------------------------------------- quality

def hnr_tilt(x, sr):
    """Harmonics-to-noise ratio in dB, and spectral tilt in dB per octave."""
    win, hop = int(0.040 * sr), int(0.020 * sr)
    hnrs, tilts = [], []
    for s in range(0, max(len(x) - win, 0), hop):
        f = x[s:s + win].astype(np.float64)
        if np.sqrt((f ** 2).mean()) < 1e-3:
            continue
        f = f * np.hanning(win)
        ac = np.correlate(f, f, 'full')[win - 1:]
        if ac[0] <= 0:
            continue
        lo, hi = int(sr / 320), int(sr / 60)
        seg = (ac[lo:hi] / ac[0]) if hi > lo else np.array([])
        if len(seg) == 0 or seg.max() <= 0:
            continue
        p = min(float(seg.max()), 0.999)
        hnrs.append(10 * np.log10(p / (1 - p)))
        X = np.fft.rfft(f)
        P = np.abs(X) ** 2
        fr = np.fft.rfftfreq(win, 1 / sr)
        band = (fr > 200) & (fr < 4000)
        if band.sum() > 8:
            tilts.append(np.polyfit(np.log2(fr[band]),
                                    10 * np.log10(P[band] + 1e-12), 1)[0])
    return (round(float(np.median(hnrs)), 1) if hnrs else None,
            round(float(np.median(tilts)), 2) if tilts else None)


def pauses(x, sr, floor_ratio=0.015, min_ms=80):
    win, hop = int(0.020 * sr), int(0.010 * sr)
    e = np.array([np.sqrt((x[s:s + win] ** 2).mean())
                  for s in range(0, max(len(x) - win, 1), hop)])
    if e.max() <= 0:
        return 0, 0
    quiet = e < floor_ratio * e.max()
    d = np.diff(np.concatenate(([0], quiet.astype(np.int8), [0])))
    st, en = np.where(d == 1)[0], np.where(d == -1)[0]
    lengths = [(b - a) * hop / sr * 1000 for a, b in zip(st, en)
               if (b - a) * hop / sr * 1000 >= min_ms]
    if not lengths:
        return 0, 0
    return len(lengths), round(float(np.median(lengths)))


def voiced_sec(engine, sid, word):
    x, sr = gen(engine, word, sid)
    if len(x) < 100:
        return None
    hop, win = int(0.010 * sr), int(0.030 * sr)
    e = np.array([np.sqrt((x[s:s + win] ** 2).mean())
                  for s in range(0, max(len(x) - win, 1), hop)])
    if e.max() <= 0:
        return None
    live = np.where(e > 0.10 * e.max())[0]
    if len(live) < 4:
        return None
    return float((live[-1] - live[0]) * hop / sr)


def rhoticity(engine, sid):
    """Duration ratio of pairs differing only by a post-vocalic /r/. ~1.0 = non-rhotic."""
    ratios = []
    for a, b in RHOTIC_PAIRS:
        da, db = voiced_sec(engine, sid, a), voiced_sec(engine, sid, b)
        if da and db and db > 0.05:
            ratios.append(da / db)
    return round(float(np.median(ratios)), 2) if ratios else None


# ------------------------------------------------------------------- mfcc

def mel_filterbank(n_filters=26, n_fft=512, sr=22050, fmin=60, fmax=7600):
    def hz2mel(f):
        return 2595 * np.log10(1 + f / 700)

    def mel2hz(m):
        return 700 * (10 ** (m / 2595) - 1)

    pts = mel2hz(np.linspace(hz2mel(fmin), hz2mel(fmax), n_filters + 2))
    bins = np.floor((n_fft + 1) * pts / sr).astype(int)
    fb = np.zeros((n_filters, n_fft // 2 + 1))
    for i in range(n_filters):
        a, b, c = bins[i], bins[i + 1], bins[i + 2]
        b = max(b, a + 1)
        c = max(c, b + 1)
        for k in range(a, min(b, fb.shape[1])):
            fb[i, k] = (k - a) / (b - a)
        for k in range(b, min(c, fb.shape[1])):
            fb[i, k] = (c - k) / (c - b)
    return fb


FB = mel_filterbank()


def dct2(v, n_out):
    """DCT-II, orthonormal; scipy is not a dependency here."""
    n = len(v)
    k = np.arange(n_out)[:, None]
    i = np.arange(n)[None, :]
    basis = np.cos(np.pi * (i + 0.5) * k / n) * np.sqrt(2.0 / n)
    basis[0] *= np.sqrt(0.5)
    return basis @ v


def timbre(x, sr, n_ceps=13):
    """Mean and spread of the first cepstral coefficients over voiced frames.

    The standard speaker-recognition feature. Distance between two voices' vectors,
    divided by the distance between two recordings of one voice, says whether a
    second voice is audibly a different person.
    """
    win, hop = int(0.025 * sr), int(0.010 * sr)
    frames = []
    for s in range(0, max(len(x) - win, 1), hop):
        f = x[s:s + win].astype(np.float64)
        if np.sqrt((f ** 2).mean()) < 5e-3 * np.abs(x).max():
            continue
        f = f * np.hamming(win)
        P = np.abs(np.fft.rfft(f, 512)) ** 2
        m = np.log(FB @ P + 1e-10)
        frames.append(dct2(m, n_ceps))
    if len(frames) < 20:
        return None
    F = np.array(frames)
    return np.concatenate([F.mean(axis=0)[1:], F.std(axis=0)[1:]])   # drop c0 = loudness


# ------------------------------------------------------------------ main

def profile(engine, sid, label, note=""):
    x, sr = gen(engine, PASSAGE, sid)
    f0, lo, hi = f0_median(x, sr)
    hnr, tilt = hnr_tilt(x, sr)
    n_pause, pause_ms = pauses(x, sr)
    thr = 0.02 * np.abs(x).max()
    v = (np.abs(x) > thr).astype(np.int8)
    d = np.diff(np.concatenate(([0], v, [0])))
    st, en = np.where(d == 1)[0], np.where(d == -1)[0]
    row = dict(
        who=label, sid=sid, note=note,
        f0=f0, f0_iqr=[lo, hi],
        wpm=round(len(PASSAGE.split()) / (len(x) / sr) * 60),
        hnr=hnr, tilt=tilt,
        silence=round(float((np.abs(x) < 0.02 * np.abs(x).max()).mean()), 3),
        pauses=n_pause, pause_med_ms=pause_ms,
        f0_range_st=f0_range_st(x, sr),
        rhotic=rhoticity(engine, sid),
        peak=round(float(np.abs(x).max()), 3),
        rms=round(float(np.sqrt((x ** 2).mean())), 4),
    )
    print(json.dumps(row))
    return row


def speaker_sheet(path):
    """VCTK's speaker-info.txt: id, age, gender, accent, region."""
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        p = line.split()
        if len(p) >= 4 and re.fullmatch(r"p\d{3}", p[0]):
            out[p[0]] = dict(age=p[1], gender=p[2], accent=p[3],
                             region=" ".join(p[4:]))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vctk", help="unpacked multi-speaker Piper bundle")
    ap.add_argument("--single", action="append", default=[], metavar="NAME=DIR",
                    help="a single-speaker bundle, repeatable")
    ap.add_argument("--espeak", required=True, help="espeak-ng-data directory")
    ap.add_argument("--sheet", help="VCTK speaker-info.txt, to label accents")
    ap.add_argument("--speakers", help="comma-separated VCTK speaker ids to measure")
    ap.add_argument("--english-only", action="store_true",
                    help="measure only speakers the sheet calls plain English males")
    ap.add_argument("--distinct", action="store_true",
                    help="also measure timbre distance between every pair")
    ap.add_argument("--out", help="write the rows to this JSON file")
    args = ap.parse_args()

    rows = []
    engines = {}

    for spec in args.single:
        if "=" not in spec:
            sys.exit(f"--single wants NAME=DIR, got {spec}")
        name, directory = spec.split("=", 1)
        engines[name] = open_bundle(directory, args.espeak)[0]
        rows.append(profile(engines[name], 0, name, "single speaker"))

    if args.vctk:
        engine, meta = open_bundle(args.vctk, args.espeak)
        smap = meta["speaker_id_map"]
        sheet = speaker_sheet(args.sheet) if args.sheet else {}

        if args.english_only:
            if not sheet:
                sys.exit("--english-only needs --sheet")
            wanted = [k for k, v in sheet.items()
                      if v["gender"] == "M" and v["accent"] == "English"]
        elif args.speakers:
            wanted = args.speakers.split(",")
        else:
            wanted = list(smap)

        print(f"# {len(wanted)} speakers, model {meta.get('dataset')}, "
              f"{meta.get('num_speakers')} total", file=sys.stderr)
        for spk in wanted:
            if spk not in smap:
                print(f"# {spk} not in this model", file=sys.stderr)
                continue
            info = sheet.get(spk, {})
            note = " ".join(filter(None, [info.get("age", ""),
                                          info.get("accent", ""),
                                          info.get("region", "")]))
            rows.append(profile(engine, smap[spk], f"vctk:{spk}", note))

        if args.distinct and rows:
            print("\n=== timbre distance between voices ===")
            vectors = {}
            for r in rows:
                if not r["who"].startswith("vctk:"):
                    continue
                x, sr = gen(engine, PASSAGE, r["sid"])
                vectors[r["who"]] = timbre(x, sr)
            base = None
            for name, eng in engines.items():
                x1, sr = gen(eng, PASSAGE, 0)
                x2, sr = gen(eng, PASSAGE.split('.')[0] + ".", 0)
                base = float(np.linalg.norm(timbre(x1, sr) - timbre(x2, sr)))
                print(json.dumps({"same_speaker_baseline": round(base, 3)}))
            if base:
                for who, vec in sorted(vectors.items()):
                    for other, ref in vectors.items():
                        if other <= who:
                            continue
                        d = float(np.linalg.norm(vec - ref))
                        print(json.dumps({"a": who, "b": other,
                                          "distance": round(d, 3),
                                          "x_baseline": round(d / base, 2)}))

    if args.out:
        json.dump(rows, open(args.out, "w", encoding="utf-8"), indent=1)
        print(f"\n# wrote {len(rows)} rows to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
