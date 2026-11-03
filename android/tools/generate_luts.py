#!/usr/bin/env python3
"""Generates Zoomify's built-in 33-point .cube LUTs into app/src/main/assets/luts/.

Creative LUTs take display-referred (gamma ~2.2) input. The Log LUT takes Zoomify Log input and must match
ZoomifyLog.kt and the shader in ZoomBlurProcessor.kt.
"""
import math
import os
import sys

SIZE = 33
LOG_C = 0.03
L0 = math.log2(LOG_C)
L1 = math.log2(1.0 + LOG_C)


def clamp(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def lum(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def saturate(c, s):
    l = lum(c)
    return [l + (v - l) * s for v in c]


def s_curve(c, k):
    return [v + k * (v * v * (3 - 2 * v) - v) for v in (clamp(x) for x in c)]


def lift(c, black, white=1.0):
    return [black + v * (white - black) for v in c]


def tint(c, t, w):
    return [v + t[i] * w for i, v in enumerate(c)]


def log_to_linear(y):
    return 2.0 ** (((y - 0.06) / 0.88) * (L1 - L0) + L0) - LOG_C


def tonemap(x, white=4.0):
    x = max(x, 0.0)
    return x * (1 + x / (white * white)) / (1 + x)


def log_to_rec709(c):
    # Undo the -1 EV exposure offset used while recording Log, roll off highlights, encode gamma 2.2.
    lin = [tonemap(log_to_linear(v) * 2.0) for v in c]
    out = [clamp(v) ** (1 / 2.2) for v in lin]
    return saturate(out, 1.05)


def teal_orange(c):
    l = lum(c)
    c = tint(c, (-0.035, 0.012, 0.055), (1 - l) ** 2)
    c = tint(c, (0.055, 0.018, -0.045), l ** 2)
    c = saturate(c, 1.12)
    return s_curve(c, 0.35)


def warm_film(c):
    c = [c[0] * 1.035, c[1] * 1.0, c[2] * 0.95]
    c = saturate(c, 0.9)
    c = s_curve(c, 0.4)
    c = [v - 0.08 * max(0.0, v - 0.8) for v in c]
    return lift(c, 0.035, 0.97)


def cool_matte(c):
    c = [c[0] * 0.97, c[1] * 1.0, c[2] * 1.04]
    c = saturate(c, 0.85)
    c = s_curve(c, 0.15)
    return lift(c, 0.06, 0.94)


def mono_classic(c):
    l = 0.4 * c[0] + 0.45 * c[1] + 0.15 * c[2]
    l = s_curve([l], 0.55)[0]
    return [l, l, l]


def bleach_bypass(c):
    l = lum(c)
    c = saturate(c, 0.45)
    c = s_curve(c, 0.7)
    return [v * 0.85 + l * 0.15 for v in c]


def golden_hour(c):
    l = lum(c)
    c = tint(c, (0.06, 0.03, -0.05), l ** 1.5)
    c = tint(c, (0.01, -0.005, 0.02), (1 - l) ** 2)
    c = saturate(c, 1.1)
    return s_curve(c, 0.25)


LUTS = [
    ("zoomify_log_to_rec709", "Zoomify Log to Rec709", log_to_rec709),
    ("teal_orange", "Teal & Orange", teal_orange),
    ("warm_film", "Warm Film", warm_film),
    ("cool_matte", "Cool Matte", cool_matte),
    ("mono_classic", "Mono Classic", mono_classic),
    ("bleach_bypass", "Bleach Bypass", bleach_bypass),
    ("golden_hour", "Golden Hour", golden_hour),
]


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for file_name, title, fn in LUTS:
        lines = [f'TITLE "{title}"', f"LUT_3D_SIZE {SIZE}", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 1 1 1"]
        for b in range(SIZE):
            for g in range(SIZE):
                for r in range(SIZE):
                    c = fn([r / (SIZE - 1), g / (SIZE - 1), b / (SIZE - 1)])
                    lines.append("%.5f %.5f %.5f" % tuple(clamp(v) for v in c))
        with open(os.path.join(out_dir, file_name + ".cube"), "w") as f:
            f.write("\n".join(lines) + "\n")
        print("wrote", file_name)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "luts"))
