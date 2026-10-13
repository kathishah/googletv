"""Sample HEIC/HEIF files and JPEG controls from a read-only SMB Guest mount and test read-only opens.

Usage: python3 -I sample_mount.py MOUNT_PATH OUT_TSV
Takes up to 25 HEIC/HEIF (at most 2 per originals subfolder) and up to 15 JPEG controls (1 per subfolder).
Prints counts only. OUT_TSV holds private relative paths: keep it outside the repository and delete it after use.
Never writes to the share and never opens the Photos library database.
"""
import os, sys, random, errno, stat, collections
mnt, out, want = sys.argv[1], sys.argv[2], 25
base = os.path.join("Photos Library.photoslibrary", "originals")
root = os.path.join(mnt, base)
subdirs = [d for d in os.listdir(root) if not d.startswith('.') and os.path.isdir(os.path.join(root, d))]
random.shuffle(subdirs)
sample, jpeg_ctrl = [], []
for sd in subdirs:
    files = [f for f in os.listdir(os.path.join(root, sd)) if not f.startswith('.')]
    random.shuffle(files)
    for f in files:
        e = f.rsplit('.',1)[-1].lower()
        if e in ("heic","heif") and len(sample) < want and sum(1 for _,r in sample if f"/{sd}/" in r) < 2: sample.append((e, f"{base}/{sd}/{f}"))
    for f in files:
        if f.rsplit('.',1)[-1].lower() in ("jpg","jpeg") and len(jpeg_ctrl) < 15 and sum(1 for _,r in jpeg_ctrl if f"/{sd}/" in r) < 1: jpeg_ctrl.append(("jpeg", f"{base}/{sd}/{f}"))
    if len(sample) >= want and len(jpeg_ctrl) >= 15: break
res = collections.Counter(); modes = collections.Counter()
with open(out, "w") as fh:
    for e, rel in sample + jpeg_ctrl:
        p = os.path.join(mnt, rel)
        m = oct(stat.S_IMODE(os.stat(p).st_mode)); modes[(e, m)] += 1
        try:
            fd = os.open(p, os.O_RDONLY)
            try: n = len(os.read(fd, 4096))
            finally: os.close(fd)
            r = "ok" if n > 0 else "empty"
        except OSError as x:
            r = errno.errorcode.get(x.errno, str(x.errno))
        res[(e, r)] += 1
        fh.write(f"{e}\t{r}\t{rel}\n")
print("subdirs", len(subdirs), "heic/heif sampled", len(sample), "jpeg controls", len(jpeg_ctrl))
print("results", dict(res)); print("modes", dict(modes))
