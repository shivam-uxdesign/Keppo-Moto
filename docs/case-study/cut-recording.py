"""Cut a ride screen recording into the case-study shots (run from docs/case-study).
Needs Python 3 with Pillow, and ffmpeg. Keep the raw recording in captures/ (git-ignored).

1. Contact sheet, to find the moments (one frame every N seconds, time-stamped):
     python cut-recording.py sheet captures/ride.mp4 [--every 10]
     -> captures/sheet-*.png
2. Cut the shots once you know the times (mm:ss), any you have:
     python cut-recording.py cut ride.mp4 --17 1:12 --17b 2:40 --17c 3:05 --17d 3:50 \
         --r2 0:00-0:45 --r3 1:00-1:25 [--blur x,y,w,h ...] [--audio]
     -> media/17-directions-dock.webp ... and media/R2-keep-moving.mp4 etc.
   --blur boxes are in the recording's own pixels (e.g. a Home chip or a contact name);
   they apply to every still and clip. Clips are H.264, 720 px wide, kept under 8 MB.
"""
import argparse, glob, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
SHEETS = os.path.join(HERE, "captures")
STILLS = {"17": "17-directions-dock", "17b": "17b-directions-filming",
          "17c": "17c-directions-jam", "17d": "17d-directions-offer",
          "14": "14-slide-keep-moving", "15": "15-where-to",
          "16a": "16a-plan-normal", "16b": "16b-plan-less-traffic", "16c": "16c-plan-no-foot-down",
          "18": "18-top-bar"}
CLIPS = {"r1": "R1-slide-to-ride", "r2": "R2-keep-moving", "r3": "R3-riding-directions"}


def ffmpeg():
    f = shutil.which("ffmpeg") or next(iter(glob.glob(os.path.expandvars(
        r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg*\*\bin\ffmpeg.exe"))), None)
    if not f:
        sys.exit("ffmpeg not found")
    return f


def secs(t):
    p = [float(x) for x in t.split(":")]
    return p[0] * 60 + p[1] if len(p) == 2 else p[0]


def blur_chain(boxes):
    """ffmpeg filter that blurs each x,y,w,h box, ending in label [v]."""
    if not boxes:
        return "[0:v]null[v]"
    parts, cur = [], "0:v"
    for i, b in enumerate(boxes):
        x, y, w, h = b.split(",")
        parts.append(f"[{cur}]split[m{i}][c{i}];[c{i}]crop={w}:{h}:{x}:{y},boxblur=20:3[b{i}];"
                     f"[m{i}][b{i}]overlay={x}:{y}[o{i}]")
        cur = f"o{i}"
    return ";".join(parts) + f";[{cur}]null[v]"


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode:
        sys.exit(r.stderr[-1500:])


def sheet(a):
    os.makedirs(SHEETS, exist_ok=True)
    out = os.path.join(SHEETS, "sheet-%02d.png")
    run([ffmpeg(), "-v", "error", "-y", "-i", a.video, "-vf",
         f"fps=1/{a.every},scale=270:-1,drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='%{{pts\\:hms}}':x=6:y=6:fontsize=22:"
         "fontcolor=yellow:box=1:boxcolor=black@0.6,tile=6x2", out])
    print("wrote", sorted(glob.glob(os.path.join(SHEETS, "sheet-*.png"))))


def cut(a):
    from PIL import Image
    os.makedirs(MEDIA, exist_ok=True)
    chain = blur_chain(a.blur)
    for key, name in STILLS.items():
        t = getattr(a, "s" + key)
        if not t:
            continue
        tmp = os.path.join(tempfile.mkdtemp(), "f.png")
        run([ffmpeg(), "-v", "error", "-y", "-ss", str(secs(t)), "-i", a.video,
             "-filter_complex", chain, "-map", "[v]", "-frames:v", "1", tmp])
        im = Image.open(tmp).convert("RGB")
        im = im.resize((780, round(im.height * 780 / im.width)), Image.LANCZOS)
        dst = os.path.join(MEDIA, name + ".webp")
        im.save(dst, "WEBP", quality=82, method=6)
        print(f"{dst}  ({os.path.getsize(dst) // 1024} KB)")
    for key, name in CLIPS.items():
        span = getattr(a, key)
        if not span:
            continue
        s, e = (secs(x) for x in span.split("-"))
        dst = os.path.join(MEDIA, name + ".mp4")
        for crf in (28, 31, 34):
            cmd = [ffmpeg(), "-v", "error", "-y", "-ss", str(s), "-t", str(e - s), "-i", a.video,
                   "-filter_complex", chain + ";[v]scale=720:-2,fps=30[o]", "-map", "[o]"]
            cmd += ["-map", "0:a?", "-c:a", "aac", "-b:a", "96k"] if a.audio else ["-an"]
            cmd += ["-c:v", "libx264", "-crf", str(crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart", dst]
            run(cmd)
            if os.path.getsize(dst) < 8 * 1024 * 1024:
                break
        print(f"{dst}  ({os.path.getsize(dst) / 1048576:.1f} MB)")


p = argparse.ArgumentParser()
sub = p.add_subparsers(dest="cmd", required=True)
s = sub.add_parser("sheet"); s.add_argument("video"); s.add_argument("--every", type=int, default=10)
c = sub.add_parser("cut"); c.add_argument("video")
for k in STILLS:
    c.add_argument("--" + k, dest="s" + k)
for k in CLIPS:
    c.add_argument("--" + k, dest=k)
c.add_argument("--blur", action="append", default=[])
c.add_argument("--audio", action="store_true", help="keep the recording's sound (your voice)")
a = p.parse_args()
sheet(a) if a.cmd == "sheet" else cut(a)
