"""Record the emulator's speaker output while tapping playback controls.

Taps are fired from this script so the audio and the actions share one clock; the
result is a wav whose envelope can be read back to see where the sound starts, where
it is cut off, and how long each utterance actually lasts.
"""
import os
import subprocess
import sys
import time
import wave

import numpy as np
import soundcard as sc

def default_adb():
    """`adb` from this machine's SDK, or whatever is on PATH.

    The path used to be hardcoded to one developer's Windows SDK, so the script would
    only run on that machine. `ANDROID_HOME` and `ANDROID_SDK_ROOT` are the standard
    variables; `ADB` overrides both.
    """
    suffix = ".exe" if os.name == "nt" else ""
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if root:
            candidate = os.path.join(root, "platform-tools", "adb" + suffix)
            if os.path.exists(candidate):
                return candidate
    return "adb"


ADB = os.environ.get("ADB") or default_adb()
DEVICE = os.environ.get("ANDROID_SERIAL", "emulator-5556")

SCRIPT = sys.argv[2] if len(sys.argv) > 2 else "rec.wav"
DURATION = float(sys.argv[3]) if len(sys.argv) > 3 else 14.0

RATE = 48000


def tap(x, y):
    subprocess.run(
        [ADB, "-s", DEVICE, "shell", "input", "tap", str(x), str(y)],
        check=False,
        capture_output=True,
    )


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: record_taps.py t,x,y[,t,x,y...] [out.wav] [seconds]")
    plan = []
    # argv[1] is a flat list: t,x,y,t,x,y,...
    args = [float(a) for a in sys.argv[1].split(",")]
    if not args or len(args) % 3 != 0:
        raise SystemExit("taps must be t,x,y triples; got %d numbers" % len(args))
    for i in range(0, len(args), 3):
        plan.append((args[i], int(args[i + 1]), int(args[i + 2])))
    plan.sort()

    speaker = sc.get_microphone(id=str(sc.default_speaker().name), include_loopback=True)
    chunks = []
    start = time.time()
    fired = set()
    with speaker.recorder(samplerate=RATE, channels=1, blocksize=2048) as rec:
        while time.time() - start < DURATION:
            now = time.time() - start
            for idx, (at, x, y) in enumerate(plan):
                if idx not in fired and now >= at:
                    fired.add(idx)
                    print(f"  t={now:5.2f}s  tap ({x},{y})", flush=True)
                    tap(x, y)
            chunks.append(rec.record(numframes=2048))
    audio = np.concatenate(chunks).reshape(-1)
    peak = float(np.abs(audio).max())
    print(f"peak={peak:.5f}")
    if peak > 0:
        audio = audio / peak * 0.9
    with wave.open(SCRIPT, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((audio * 32767).astype(np.int16).tobytes())
    print(f"wrote {SCRIPT}  ({len(audio)/RATE:.2f}s)")

    # Envelope: 20 ms windows, and the runs of loud vs quiet that fall out of it.
    win = int(RATE * 0.02)
    frames = len(audio) // win
    env = np.abs(audio[: frames * win]).reshape(frames, win).max(axis=1)
    loud = env > max(0.05, env.max() * 0.08)
    runs = []
    cur = loud[0]
    length = 1
    for v in loud[1:]:
        if v == cur:
            length += 1
        else:
            runs.append((cur, length * 0.02))
            cur, length = v, 1
    runs.append((cur, length * 0.02))
    print("\nsegments (start_s, kind, dur_s):")
    at = 0.0
    for is_loud, dur in runs:
        if dur >= 0.06:
            print(f"  {at:6.2f}  {'SOUND' if is_loud else 'silence':7s} {dur:6.2f}")
        at += dur


main()
