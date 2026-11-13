#!/usr/bin/env python3
"""Writes Type to accept's "Sound when correct" (app/src/main/res/raw/lab_type_to_accept_correct.wav).

Usage: python3 scripts/make_correct_sound.py app/src/main/res/raw/lab_type_to_accept_correct.wav
Original and generated here; see app/src/main/assets/lab_sounds_SOURCES.md.
"""
import math, struct, sys, wave

# Two soft sine notes, C6 then G6, overlapping, each with a 5 ms attack and a fast decay.
rate = 22050
notes = [(1046.5, 0.0, 0.16), (1568.0, 0.06, 0.20)]
length = 0.26
samples = []
for i in range(int(rate * length)):
    t = i / rate
    v = 0.0
    for freq, start, dur in notes:
        u = t - start
        if 0 <= u < dur:
            attack = min(1.0, u / 0.005)
            decay = math.exp(-u * 18)
            v += math.sin(2 * math.pi * freq * u) * attack * decay * 0.22
    samples.append(int(max(-1, min(1, v)) * 32767))
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(rate)
    w.writeframes(b''.join(struct.pack('<h', s) for s in samples))
