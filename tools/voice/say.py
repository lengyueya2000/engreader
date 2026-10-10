"""Hear what the bundled voice actually says, by synthesising and transcribing it back.

This is the tool that found the four shapes `SpokenText` rewrites. It exists so
those numbers can be re-derived rather than trusted, and so a future edit to the
rewrite can be checked against the voice rather than against an intention.

    pip install sherpa-onnx faster-whisper numpy
    python say.py --voice <unpacked Piper voice dir> --espeak <espeak-ng-data dir>

`--espeak` is the `espeak-ng-data` directory from inside any Piper bundle; the
same copy ships in the APK under `assets/tts/`. `--voice` is a directory holding
`model.onnx` and `tokens.txt`, again as shipped.

Three modes, because the four defects needed three different measurements:

  (default)   synthesise each probe, transcribe it, print what was heard. This is
              how `CHAPTER VI.` was found to be "chapter vee eye" and
              `8:35 P. M.` to be "eight thirty-five p, M" — the durations were
              similar, only the words were wrong.
  --duration  print the length of each probe, averaged over --repeat runs. This is
              how the year rewrite was justified: `1837` in a carrier is 3.34 s of
              audio, `eighteen thirty-seven` is 1.50 s. It is also the measurement
              behind the claim that weak forms are already correct — `to` reduced
              is shorter than `to` spelled out, so rewriting it would be wrong.
  --words     print per-word timings of one probe. This is how linking was checked:
              over "It is a truth universally acknowledged, that a single man" every
              gap between words inside a phrase is 0.000 s, and the only gap at all
              is the 0.700 s one at the comma. Nothing between the words needs
              rewriting, which is why the rewrite leaves connected speech alone.
              The timestamps are Whisper's own and run longer than the audio; read
              the gaps, not the absolute times.

VITS synthesis carries random noise, so a single run will occasionally mislead:
`--repeat` averages the durations, and a verdict that rests on a difference
smaller than the run-to-run spread is not a verdict. Do not trust one run.
"""

import argparse
import os
import sys

import numpy as np
import sherpa_onnx

# The shapes that were measured as wrong, each with the rewrite that fixes it.
# Kept beside the probes so a re-run shows both sides of the change.
PROBES = [
    ("year in a date frame",
     "it occurred to me, in 1837, that something might perhaps be made out",
     "it occurred to me, in eighteen thirty-seven, that something might perhaps be made out"),
    ("year after a date word",
     "these I enlarged in 1844 into a sketch of the conclusions",
     "these I enlarged in eighteen forty-four into a sketch of the conclusions"),
    ("chapter heading",
     "CHAPTER VI.",
     "Chapter six."),
    ("regnal / part numeral",
     "Part IV",
     "Part four"),
    ("dotted meridiem",
     "Left Munich at 8:35 P. M., on 1st May",
     "Left Munich at 8:35 p m, on 1st May"),
    ("illustration note",
     'no objection to hearing it. [Illustration: "He came down" '
     "[Copyright 1894 by George Allen.]]",
     "no objection to hearing it."),
    ("asterisk emphasis",
     "it was **very** important and _quite_ true",
     "it was very important and quite true"),
    # Controls: measured as already correct, so the rewrite must leave them alone.
    ("control: contraction",
     "It's a matter of no importance.",
     "It's a matter of no importance."),
    ("control: title abbreviation",
     "Mr. Bennet was among the earliest.",
     "Mr. Bennet was among the earliest."),
    ("control: clock time",
     "It was 5 p.m. on Tuesday.",
     "It was 5 p.m. on Tuesday."),
]


def build_tts(voice_dir, espeak_dir, threads):
    model = os.path.join(voice_dir, "model.onnx")
    tokens = os.path.join(voice_dir, "tokens.txt")
    for path in (model, tokens, espeak_dir):
        if not os.path.exists(path):
            sys.exit(f"missing: {path}")
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=model, tokens=tokens, data_dir=espeak_dir),
            num_threads=threads),
        rule_fsts="", max_num_sentences=1)
    return sherpa_onnx.OfflineTts(config)


def build_asr(model_size, cache_dir):
    from faster_whisper import WhisperModel
    return WhisperModel(model_size, device="cpu", compute_type="int8",
                        download_root=cache_dir)


def say(tts, text):
    audio = tts.generate(text, sid=0, speed=1.0)
    return np.array(audio.samples, dtype=np.float32), audio.sample_rate


def hear(asr, samples, words=False):
    segments, _ = asr.transcribe(samples, language="en", beam_size=5,
                                 word_timestamps=words, vad_filter=False)
    segments = list(segments)
    if words:
        return segments, [w for s in segments for w in s.words]
    return " ".join(s.text.strip() for s in segments).strip()


def durations(tts, text, repeat):
    total = 0.0
    for _ in range(repeat):
        samples, rate = say(tts, text)
        total += len(samples) / rate
    return total / repeat


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--voice", required=True, help="unpacked Piper voice directory")
    parser.add_argument("--espeak", required=True, help="espeak-ng-data directory")
    parser.add_argument("--asr", default="small", help="faster-whisper model size")
    parser.add_argument("--cache", default=None, help="faster-whisper download cache")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--repeat", type=int, default=3,
                        help="synthesis runs per probe in --duration mode")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--duration", action="store_true",
                      help="compare audio length of the written and spoken forms")
    mode.add_argument("--words", metavar="TEXT",
                      help="print per-word timings for TEXT, to check linking")
    args = parser.parse_args()

    tts = build_tts(args.voice, args.espeak, args.threads)
    print(f"voice {args.voice}  sample rate {tts.sample_rate}")

    if args.words:
        samples, rate = say(tts, args.words)
        asr = build_asr(args.asr, args.cache)
        segments, words = hear(asr, samples, words=True)
        print(f"total {len(samples) / rate:.2f}s")
        previous = None
        for word in words:
            gap = "" if previous is None else f"  gap {word.start - previous:+.3f}s"
            print(f"  {word.start:6.2f}-{word.end:6.2f}  {word.word.strip()}{gap}")
            previous = word.end
        return

    if args.duration:
        print(f"{'written':>34} {'s':>6}   {'spoken':>34} {'s':>6}   ratio")
        for name, written, spoken in PROBES:
            written_s = durations(tts, written, args.repeat)
            spoken_s = durations(tts, spoken, args.repeat)
            print(f"{written[:34]:>34} {written_s:6.2f}   {spoken[:34]:>34} "
                  f"{spoken_s:6.2f}   {written_s / spoken_s:5.2f}")
        print("\nA ratio near 1.00 means the rewrite changes nothing audible and is "
              "therefore not justified — that is the expected result for the controls.")
        return

    asr = build_asr(args.asr, args.cache)
    for name, written, spoken in PROBES:
        print("=" * 72)
        print(f"[{name}]")
        for label, text in (("written", written), ("spoken", spoken)):
            samples, rate = say(tts, text)
            print(f"  {label}: {text}")
            print(f"    heard: {hear(asr, samples)}")


if __name__ == "__main__":
    main()
