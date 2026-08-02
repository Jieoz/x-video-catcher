#!/usr/bin/env python3
"""Verify the corrected MediaUrls against all three captures AND the live CDN.

A string comparison would have passed the previous version, which 404'd on PNG photos.
So every rewritten URL from the real capture is actually fetched here, and compared
against every other size the CDN offers for that photo.
"""
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

MEDIA_KINDS = re.compile(r"/(amplify_video|ext_tw_video|tweet_video)/", re.I)
VIDEO_HOSTS = ["video.twimg.com", "video-ft.twimg.com", "amp.twimg.com"]
PHOTO_HOSTS = ["pbs.twimg.com", "pbs-ft.twimg.com"]
MEDIA_PATH = re.compile(r"(\.m3u8|\.mp4|\.m4s|\.ts)(\?|$)|/pl/|/vid/|/aud/|/seg/", re.I)
PHOTO_PATH = re.compile(
    r"/(media|tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/", re.I)
PHOTO_EXCLUDED = re.compile(
    r"/(profile_images|profile_banners|emoji|card_img|semantic_core_img|hashflag|ads-payload)/", re.I)
MASTER = re.compile(
    r"/(?:amplify_video|ext_tw_video|tweet_video)/\d+/(?:pu/)?pl/[A-Za-z0-9_-]+\.m3u8", re.I)
LARGEST_SIZE = "4096x4096"


def interesting_photo(low):
    return (any(h in low for h in PHOTO_HOSTS)
            and not PHOTO_EXCLUDED.search(low)
            and bool(PHOTO_PATH.search(low)))


def interesting_video(low):
    if not MEDIA_PATH.search(low) and not MEDIA_KINDS.search(low):
        return False
    return any(h in low for h in VIDEO_HOSTS) or bool(MEDIA_KINDS.search(low))


def is_interesting(url):
    if not url or not url.lower().startswith("http"):
        return False
    low = url.lower()
    return interesting_video(low) or interesting_photo(low)


def highest_quality_photo(url):
    if not interesting_photo(url.lower()):
        return url
    frag = ""
    if "#" in url:
        i = url.index("#")
        frag, url = url[i:], url[:i]
    path, _, query = url.partition("?")
    params = []
    for part in query.split("&"):
        if part and "=" in part:
            k, _, v = part.partition("=")
            if k:
                params.append((k, v))
    dot, slash = path.rfind("."), path.rfind("/")
    if dot > slash:
        ext = path[dot + 1:].lower()
        if ext in {"jpg", "jpeg", "png", "webp", "gif"}:
            path = path[:dot]
            if not any(k.lower() == "format" for k, _ in params):
                params.append(("format", "jpg" if ext == "jpeg" else ext))
    rebuilt = [(k, v) for k, v in params if k.lower() != "name"]
    rebuilt = [(k, v) for k, v in rebuilt
               if not (k.lower() == "format" and v.lower() == "webp")]
    if not any(k.lower() == "format" for k, _ in rebuilt):
        rebuilt.append(("format", "jpg"))
    rebuilt.append(("name", LARGEST_SIZE))
    return path + "?" + "&".join(f"{k}={v}" for k, v in rebuilt) + frag


# ---------------------------------------------------------------- load captures
cands = []
for p in sys.argv[1:]:
    for line in open(p, encoding="utf-8"):
        line = line.strip()
        if line:
            r = json.loads(line)
            if "url" in r:
                cands.append(r)
print(f"loaded {len(cands)} candidate records from {len(sys.argv)-1} captures")

# ---------------------------------------------------------- 1. no regression
dropped = [r["url"] for r in cands if not is_interesting(r["url"])]
lost = [u for u in dropped if "robots.txt" not in u]
print(f"\n[1] filter: {len(cands)-len(dropped)} pass, {len(dropped)} dropped, "
      f"{len(lost)} real media lost")
for u in lost:
    print("    LOST:", u)

# ------------------------------------------------- 2. ext_tw_video master fix
ext_pl = sorted({r["url"] for r in cands
                 if "/ext_tw_video/" in r["url"] and ".m3u8" in r["url"]})
print("\n[2] ext_tw_video playlists — master detection:")
bad = 0
for u in ext_pl:
    got = bool(MASTER.search(u))
    # a master has no codec/resolution dir between pl/ and the key
    tail = u.split("/pl/", 1)[1].split("?")[0]
    want = "/" not in tail
    flag = "ok " if got == want else "BAD"
    if got != want:
        bad += 1
    print(f"    {flag} master={got!s:5} expect={want!s:5} {u}")
print(f"    mismatches: {bad}")

# --------------------------------------------------- 3. live CDN verification
photos = sorted({r["url"] for r in cands if interesting_photo(r["url"].lower())})
print(f"\n[3] live CDN check on {len(photos)} distinct captured photo URLs")


def fetch(url):
    out = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code} %{size_download} %{content_type}",
         "-L", "--max-time", "30", url],
        capture_output=True, text=True).stdout.split()
    if len(out) < 2:
        return (0, 0, "")
    return (int(out[0]), int(out[1]), out[2] if len(out) > 2 else "")


rewritten = {u: highest_quality_photo(u) for u in photos}
targets = sorted(set(rewritten.values()))
with ThreadPoolExecutor(max_workers=8) as ex:
    res = dict(zip(targets, ex.map(fetch, targets)))

fails = [(u, res[u]) for u in targets if res[u][0] != 200 or res[u][1] == 0]
print(f"    rewritten URLs fetched: {len(targets)}, non-200/empty: {len(fails)}")
for u, r in fails:
    print(f"    FAIL {r} {u}")

# Compare against what X itself asked for. Byte count is NOT the measure: a larger
# image re-encoded can be fewer bytes (a captured thumb went 675x1200/50119B ->
# 720x1280/48498B, i.e. more pixels in a smaller file). Decode the dimensions instead.
print("\n[4] rewritten vs the size X requested — compared by PIXELS, not bytes")


def pixels(path):
    """Width*height from a JPEG/PNG/WebP header, without a decoder dependency."""
    d = open(path, "rb").read()
    if d[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", d[16:24])
        return w * h, f"{w}x{h}"
    if d[:2] == b"\xff\xd8":
        i = 2
        while i < len(d) - 9:
            if d[i] != 0xFF:
                i += 1
                continue
            m = d[i + 1]
            if m in (0xC0, 0xC1, 0xC2, 0xC3):
                h, w = struct.unpack(">HH", d[i + 5:i + 9])
                return w * h, f"{w}x{h}"
            if m in (0xD8, 0xD9) or 0xD0 <= m <= 0xD7:
                i += 2
                continue
            i += 2 + struct.unpack(">H", d[i + 2:i + 4])[0]
        return 0, "?"
    if d[:4] == b"RIFF" and d[8:12] == b"WEBP":
        if d[12:16] == b"VP8X":
            w = int.from_bytes(d[24:27], "little") + 1
            h = int.from_bytes(d[27:30], "little") + 1
            return w * h, f"{w}x{h}"
        if d[12:16] == b"VP8 ":
            w = int.from_bytes(d[26:28], "little") & 0x3FFF
            h = int.from_bytes(d[28:30], "little") & 0x3FFF
            return w * h, f"{w}x{h}"
    return 0, "?"


def download(args):
    url, dest = args
    subprocess.run(["curl", "-s", "-L", "--max-time", "30", "-o", dest, url],
                   capture_output=True)
    try:
        return pixels(dest)
    except Exception:
        return 0, "?"


tmpdir = tempfile.mkdtemp()
jobs_a = [(u, f"{tmpdir}/a{i}") for i, u in enumerate(photos)]
jobs_b = [(rewritten[u], f"{tmpdir}/b{i}") for i, u in enumerate(photos)]
with ThreadPoolExecutor(max_workers=8) as ex:
    px_a = list(ex.map(download, jobs_a))
    px_b = list(ex.map(download, jobs_b))

smaller = []
for u, a, b in zip(photos, px_a, px_b):
    if a[0] and b[0] and b[0] < a[0]:
        smaller.append((u, a[1], rewritten[u], b[1]))
print(f"    photos with FEWER pixels after rewrite: {len(smaller)}")
for u, da, v, db in smaller:
    print(f"    SMALLER {da} -> {db}\n       from {u}\n       to   {v}")
gained = sum(1 for a, b in zip(px_a, px_b) if a[0] and b[0] and b[0] > a[0])
same = sum(1 for a, b in zip(px_a, px_b) if a[0] and b[0] and b[0] == a[0])
print(f"    more pixels: {gained}, identical: {same}")

# The comparison above is only meaningful if the decoder actually decoded. A parser
# that silently returns 0 would report "no photo got smaller" on every input.
undecoded = sum(1 for a, b in zip(px_a, px_b) if not a[0] or not b[0])
print(f"    [self-test] photos the pixel parser could not read: {undecoded} "
      f"(must be 0, else the check above proves nothing)")
shutil.rmtree(tmpdir, ignore_errors=True)

# Is there any size that beats ours? Probe the full ladder on a sample.
print("\n[5] is 4096x4096 really the max? probing the ladder on captured photos")
KEY = re.compile(r"/media/([A-Za-z0-9_-]+)")
keys = [KEY.search(u).group(1).split(".")[0] for u in photos if KEY.search(u)]
sample = sorted(set(keys))[:10]
ladder = ["large", "4096x4096", "orig"]
beaten = 0
for k in sample:
    fmt = "png" if any(f"/media/{k}?format=png" in u for u in photos) else "jpg"
    sizes = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        urls = [f"https://pbs.twimg.com/media/{k}?format={fmt}&name={n}" for n in ladder]
        for n, r in zip(ladder, ex.map(fetch, urls)):
            sizes[n] = r
    ours = sizes["4096x4096"]
    best = max((v[1] for v in sizes.values() if v[0] == 200), default=0)
    mark = "ok " if ours[0] == 200 and ours[1] >= best else "BAD"
    if not (ours[0] == 200 and ours[1] >= best):
        beaten += 1
    print(f"    {mark} {k} fmt={fmt} " + " ".join(
        f"{n}={sizes[n][0]}:{sizes[n][1]}" for n in ladder))
print(f"    photos where some other size beat ours: {beaten}")

ok = (not lost and bad == 0 and not fails and not smaller and beaten == 0
      and undecoded == 0)
print("\nALL OK" if ok else "\nPROBLEMS FOUND")
