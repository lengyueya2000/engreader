"""Measure the channel a cloning voice copies its clarity from.

Why this exists: the first shipped Attenborough reference was picked by ear and by
likeness, and the voice it produced was unmistakably *him* but muffled — "like talking
with something in the mouth". The clip's numbers showed why, and not in the way the
first guess expected: its bandwidth was fine (the spectrum gave up at 11.6 kHz, the
replacement at 11.5 kHz), but it carried 16.6 dB of noise under the voice against the
replacement's 39.6 dB. A zero-shot clone copies the channel as faithfully as the
timbre, so whatever the prompt was recorded through arrives in every sentence.

Measured on the shipped replacement, synthesising the same twelve-sentence passage with
each prompt in turn, the output's energy above 4 kHz rose about 3.5 dB and its frame
SNR about 2.8 dB; the word error rate did not move (0.067 to 0.070 — the old clip was
already intelligible, it simply sounded dull). So judge a clip by its channel, and
judge the *voice* by synthesising with it, which is `measure.py`'s job.

## What is measured, and which numbers are trustworthy

Channel properties — these describe the recording and transfer between clips:

  snr           Loud frames over quiet frames. The defect that actually made the first
                shipped clip unusable as a prompt: 16.6 dB of room tone under the voice,
                against 39.6 dB for the replacement. A clone copies the noise floor into
                every sentence, and it reads as "mumbled" rather than as background hiss.
  hard_cutoff   Where the spectrum gives up. This is the giveaway for a re-encode: a
                container claiming 48 kHz that stops near 8 kHz was made from a
                narrow-band source, and the bitrate alone will not tell you.

Content properties — do NOT gate on these:

  r20 / r30 / b99 / hf_4_8k
                How far the long-term spectrum reaches relative to its own peak. These
                are dominated by *what is being said*: a sentence full of fricatives
                measures brighter than the same speaker reading a sentence of long
                vowels. The replacement clip scored a lower r20 (797 Hz) than the clip
                it replaced (867 Hz) while producing clearly brighter speech, because
                the old number was describing "human beings that interested me", not
                the channel. An earlier version of this tool gated on r20 and failed
                both clips for exactly that reason.

The synthesis-side check that does settle the question is `measure.py`'s WER plus the
4-8 kHz energy of the *output*, which is what the user actually hears.

## Two checks this deliberately does not replace

  - Transcription agreement: `pick_reference.py` owns it. A prompt_text that does not
    match the audio misaligns every synthesis, and shortening a clip to make the voice
    cheaper to run did exactly that — a truncated prompt scored 0.86 word error
    against 0.05 for the full one.
  - Speaker identity: `speaker_check.py` owns it. The brightest clip in the pool was
    the *wrong man*, and no bandwidth number can tell you that.

Run:

    pip install numpy            # plus ffmpeg on PATH, or pip install imageio-ffmpeg
    python measure_bandwidth.py reference.wav [more.wav ...]
"""

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import wave

import numpy as np


# ------------------------------------------------------------------ decoding

def ffmpeg_exe():
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg
    except ImportError:
        sys.exit("no ffmpeg on PATH and imageio-ffmpeg is not installed")
    return imageio_ffmpeg.get_ffmpeg_exe()


def read_wav(path):
    """Decode to mono float64 in [-1, 1].

    ffmpeg first, because Python's `wave` refuses a float-encoded WAV outright and
    32-bit float is what most editors write for a master take. `pcm_s16le` is the
    contract: the widths below are then exactly the two shapes the reader handles.
    """
    try:
        with wave.open(path, "rb") as handle:
            # `getcomptype` answers with a str ('NONE'), not bytes — comparing it to
            # b"NONE" makes every plain PCM file look unreadable.
            if handle.getcomptype() != "NONE":
                raise wave.Error("compressed or float payload")
            frames, channels, width = (handle.getnframes(), handle.getnchannels(),
                                       handle.getsampwidth())
            raw = handle.readframes(frames)
            rate = handle.getframerate()
    except (wave.Error, EOFError):
        # `pipe:1` needs the format named explicitly: ffmpeg picks the muxer from the
        # filename, and a pipe has no extension to read.
        probe = subprocess.run(
            [ffmpeg_exe(), "-hide_banner", "-loglevel", "error", "-y", "-i", path,
             "-vn", "-ac", "1", "-ar", "48000", "-c:a", "pcm_s16le", "-f", "wav",
             "pipe:1"], capture_output=True)
        if probe.returncode != 0:
            raise SystemExit(f"{path}: cannot decode — {probe.stderr.decode(errors='replace')[:200]}")
        # Strip the RIFF header the muxer wrote so the payload can be read as samples.
        with wave.open(io.BytesIO(probe.stdout), "rb") as handle:
            channels, width, rate = handle.getnchannels(), handle.getsampwidth(), handle.getframerate()
            raw = handle.readframes(handle.getnframes())
    if width == 2:
        data = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif width == 4:
        data = np.frombuffer(raw, dtype="<i4").astype(np.float64) / 2147483648.0
    else:
        raise SystemExit(f"{path}: unsupported sample width {width} bytes")
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return np.ascontiguousarray(data, dtype=np.float64), rate


# ------------------------------------------------------------------- metrics

def long_term_spectrum(samples, rate, n=2048):
    hop = n // 2
    if len(samples) < 2 * n:
        return None, None
    window = np.hanning(n)
    frames = np.array([samples[i:i + n] * window
                       for i in range(0, len(samples) - n, hop)])
    power = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    return np.fft.rfftfreq(n, 1.0 / rate), power


def rollover(freqs, db, threshold):
    """Highest frequency still at or above `threshold` dB relative to the peak."""
    above = np.nonzero(db >= threshold)[0]
    return float(freqs[above[-1]]) if len(above) else 0.0


def metrics(samples, rate):
    freqs, power = long_term_spectrum(samples, rate)
    if power is None:
        return None
    lt = power.mean(axis=0)
    lt_db = 10 * np.log10(lt + 1e-20)
    peak = lt_db.max()

    cumulative = np.cumsum(lt) / lt.sum()
    b99 = float(freqs[min(np.searchsorted(cumulative, 0.99), len(freqs) - 1)])

    # Speech frames only: taking the loudest 30% keeps the room tone out of the
    # high-frequency figure, which a whole-file average would smear.
    frame_energy = power.sum(axis=1)
    speech = power[frame_energy >= np.percentile(frame_energy, 70)].mean(axis=0)
    high = (freqs >= 4000) & (freqs < 8000)
    hf = 10 * np.log10((speech[high].sum() + 1e-20) / (speech.sum() + 1e-20))

    snr = 10 * np.log10(np.percentile(frame_energy, 90) /
                        (np.percentile(frame_energy, 20) + 1e-20))

    _, hard_power = long_term_spectrum(samples, rate, n=4096)
    hard = 0.0
    if hard_power is not None:
        hlt = 10 * np.log10(hard_power.mean(axis=0) + 1e-20)
        hard = rollover(np.fft.rfftfreq(4096, 1.0 / rate), hlt, hlt.max() - 50)

    return dict(
        seconds=round(len(samples) / rate, 2), rate=rate,
        peak=round(float(np.abs(samples).max()), 3),
        rms=round(float(np.sqrt((samples ** 2).mean())), 4),
        r20=round(rollover(freqs, lt_db, peak - 20)),
        r30=round(rollover(freqs, lt_db, peak - 30)),
        b99=round(b99), hard_cutoff=round(hard),
        hf_4_8k=round(float(hf), 1), snr=round(float(snr), 1),
    )


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wavs", nargs="+")
    ap.add_argument("--json", help="write the numbers here as well as printing them")
    ap.add_argument("--min-snr", type=float, default=25.0,
                    help="below this there is noise or music under the voice; the "
                         "rejected first clip measured 16.6 dB, the replacement 39.6")
    ap.add_argument("--min-cutoff", type=int, default=10000,
                    help="a spectrum that gives up below this many Hz was re-encoded "
                         "from a narrow-band source")
    args = ap.parse_args()

    rows = []
    for path in args.wavs:
        samples, rate = read_wav(path)
        m = metrics(samples, rate)
        if m is None:
            print(f"{path}: too short to measure", file=sys.stderr)
            continue
        m["file"] = os.path.basename(path)
        reasons = []
        if m["snr"] < args.min_snr:
            reasons.append(f"snr {m['snr']}dB < {args.min_snr}dB (noise floor)")
        if m["hard_cutoff"] < args.min_cutoff:
            reasons.append(f"hard cutoff {m['hard_cutoff']}Hz < {args.min_cutoff}Hz "
                           "(narrow-band source)")
        # r20 / r30 / b99 / hf_4_8k are printed for comparison but not gated on: they
        # describe the sentence, not the channel. See the module docstring.
        m["usable"] = not reasons
        m["reasons"] = reasons
        rows.append(m)

    cols = ("file", "seconds", "rate", "r20", "r30", "b99", "hard_cutoff",
            "hf_4_8k", "snr", "rms", "peak", "usable")
    print("  ".join(f"{c:>{max(len(c), 7)}}" for c in cols))
    for r in rows:
        print("  ".join(f"{str(r[c]):>{max(len(c), 7)}}" for c in cols))

    for r in rows:
        if not r["usable"]:
            print(f"\n✗ {r['file']}: " + "; ".join(r["reasons"]), file=sys.stderr)

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(rows, handle, ensure_ascii=False, indent=1)
        print(f"\nwrote {args.json}")

    # A usable clip is the point of the tool; exit nonzero so it can gate a rebuild.
    if not any(r["usable"] for r in rows):
        sys.exit(1)


if __name__ == "__main__":
    main()
