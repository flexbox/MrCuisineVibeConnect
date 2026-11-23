#!/usr/bin/env python3
# Offline helpers for tools/setup/toque-setup.sh. Nothing here talks to the robot: it only reads
# and writes files on the Mac (or Linux PC). Run with the system Python 3 in isolated mode:
#
#   python3 -I bootimg_tool.py kernel-version <image>
#   python3 -I bootimg_tool.py same-kernel <boot-original.img> <twrp.img>
#   python3 -I bootimg_tool.py own-twrp <boot-original.img> <twrp.img> <out.img>
#   python3 -I bootimg_tool.py gpt <dump-a.bin> <dump-b.bin> <boot-offset> <recovery-offset> <size>
#
# own-twrp is a line-by-line port of Step-OwnTwrp in Wuki60/MrCuisineVibeConnect v0.7.1
# (tools/setup/toque-setup.ps1): TWRP's header and ramdisk, the robot's own kernel + device tree.
# Exit codes: 0 done, 2 refused (message starts with "STOP:"), 10 (same-kernel / own-twrp) the
# guide's TWRP already carries this robot's kernel.
import gzip
import hashlib
import io
import math
import re
import struct
import sys
import zlib

PARTITION_SIZE = 16777216


def stop(msg):
    print("STOP: " + msg)
    sys.exit(2)


def read_boot_image(path, what):
    """Android boot image, header version 0 (boot, recovery and TWRP on this robot all use it)."""
    with open(path, "rb") as f:
        b = f.read()
    if len(b) < 4096 or b[:8] != b"ANDROID!":
        stop(f"{what} is not an Android boot image: {path}")
    (k_size, k_addr, r_size, r_addr, s_size, s_addr, tags, page, dt_size) = struct.unpack_from("<9I", b, 8)
    if page < 2048 or page > 16384 or page & (page - 1):
        stop(f"{what} has an unexpected page size ({page}).")
    k_off = page
    r_off = k_off + math.ceil(k_size / page) * page
    if k_size <= 0 or r_size <= 0 or r_off + r_size > len(b):
        stop(f"{what} is truncated or its header is invalid.")
    return {
        "bytes": b, "page": page, "kernel_addr": k_addr, "ramdisk_addr": r_addr,
        "second_size": s_size, "second_addr": s_addr, "tags_addr": tags, "dt_size": dt_size,
        "kernel": b[k_off:k_off + k_size], "ramdisk": b[r_off:r_off + r_size],
    }


def boot_image_id(kernel, ramdisk):
    """The id mkbootimg stores at offset 576: SHA-1 over each part then its 32-bit size (second: empty)."""
    h = hashlib.sha1()
    for part in (kernel, ramdisk, b""):
        h.update(part)
        h.update(struct.pack("<I", len(part)))
    return h.digest()


def kernel_version(kernel):
    """'Linux version ...' of a zImage: first gzip stream found in its first 128 KiB."""
    limit = min(len(kernel) - 3, 131072)
    i = kernel.find(b"\x1f\x8b\x08", 0, limit + 3)
    while 0 <= i < limit:
        d = zlib.decompressobj(16 + zlib.MAX_WBITS)
        try:
            out = d.decompress(kernel[i:], 32 * 1024 * 1024)
        except zlib.error:
            out = b""
        m = re.search(rb"Linux version [\x20-\x7e]+", out)
        if m:
            return m.group(0).decode("ascii")
        i = kernel.find(b"\x1f\x8b\x08", i + 1, limit + 3)
    return "(version not found)"


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def cmd_kernel_version(path):
    print(kernel_version(read_boot_image(path, path)["kernel"]))


def cmd_same_kernel(boot, twrp):
    r = read_boot_image(boot, "boot-original.img")
    t = read_boot_image(twrp, "TWRP")
    print("Robot kernel: " + kernel_version(r["kernel"]))
    print("TWRP kernel : " + kernel_version(t["kernel"]))
    sys.exit(10 if sha256(r["kernel"]) == sha256(t["kernel"]) else 0)


def cmd_own_twrp(boot, twrp, out):
    r = read_boot_image(boot, "boot-original.img")
    t = read_boot_image(twrp, "TWRP")
    print("Robot kernel: " + kernel_version(r["kernel"]))
    print("TWRP kernel : " + kernel_version(t["kernel"]))
    if sha256(r["kernel"]) == sha256(t["kernel"]):
        print("The guide's TWRP already carries this robot's kernel: nothing to build.")
        sys.exit(10)
    # Same layout, or the robot's bootloader would not load the result.
    for f in ("page", "kernel_addr", "ramdisk_addr", "second_addr", "tags_addr"):
        if r[f] != t[f]:
            stop(f"The robot's boot image and TWRP differ on {f} (0x{r[f]:x} / 0x{t[f]:x}): not building. "
                 "Report this robot's model and image version.")
    if r["second_size"] or t["second_size"] or r["dt_size"] or t["dt_size"]:
        stop("A second stage or a separate device tree is present: not handled, not building.")
    for p in (r["kernel"], r["ramdisk"], t["kernel"], t["ramdisk"]):
        if struct.unpack_from("<I", p, 0)[0] == 0x58881688:
            stop("MediaTek sub-header found in one of the images: not handled, not building.")
    if len(r["kernel"]) < 0x28 or struct.unpack_from("<I", r["kernel"], 0x24)[0] != 0x016F2818:
        stop("The robot's kernel is not an ARM zImage: not building.")
    if t["ramdisk"][:2] != b"\x1f\x8b":
        stop("TWRP's ramdisk is not gzip: not building.")
    # The ramdisk must really be a complete gzip stream (catches a damaged TWRP download).
    try:
        gzip.GzipFile(fileobj=io.BytesIO(t["ramdisk"])).read()
    except (OSError, EOFError, zlib.error):
        stop("TWRP's ramdisk does not decompress: not building.")

    page = t["page"]
    k_pad = math.ceil(len(r["kernel"]) / page) * page
    r_pad = math.ceil(len(t["ramdisk"]) / page) * page
    size = page + k_pad + r_pad
    if size > PARTITION_SIZE:
        stop(f"The result would be {size} bytes: larger than the partition.")
    img = bytearray(size)
    img[0:page] = t["bytes"][0:page]                                  # TWRP's header: command line, load addresses
    struct.pack_into("<I", img, 8, len(r["kernel"]))                  # kernel_size
    struct.pack_into("<I", img, 16, len(t["ramdisk"]))                # ramdisk_size
    img[576:608] = bytes(32)
    img[576:596] = boot_image_id(r["kernel"], t["ramdisk"])           # id
    img[page:page + len(r["kernel"])] = r["kernel"]                   # the robot's kernel and device tree
    img[page + k_pad:page + k_pad + len(t["ramdisk"])] = t["ramdisk"] # TWRP itself
    with open(out, "wb") as f:
        f.write(img)

    # Read the result back from disk and compare each part with its source.
    o = read_boot_image(out, "twrp-own-kernel.img")
    if sha256(o["kernel"]) != sha256(r["kernel"]) or sha256(o["ramdisk"]) != sha256(t["ramdisk"]):
        stop("The image written does not read back correctly: nothing to flash.")
    if o["bytes"][576:596] != boot_image_id(o["kernel"], o["ramdisk"]):
        stop("The image written has a wrong header id: nothing to flash.")
    print(f"Robot kernel + device tree: {len(r['kernel'])} bytes, SHA-256 {sha256(r['kernel'])}")
    print(f"TWRP ramdisk              : {len(t['ramdisk'])} bytes, SHA-256 {sha256(t['ramdisk'])}")
    print(f"Built {out}: {size} bytes, SHA-256 {sha256(bytes(img))}")


def cmd_gpt(dump_a, dump_b, boot_off, rec_off, size):
    """Checks a GPT read twice from the start of EMMC_USER, and that boot and recovery are where the
    script reads and writes them. Prints the partition table."""
    with open(dump_a, "rb") as f:
        a = f.read()
    with open(dump_b, "rb") as f:
        b = f.read()
    if len(a) < 34 * 512 or a != b:
        stop("The two readbacks of the partition table differ or are too short: the USB link is not reliable. Nothing was written.")
    hdr = a[512:1024]
    if hdr[:8] != b"EFI PART":
        stop("No GPT at the start of EMMC_USER: this is not the expected layout. Do not flash.")
    hsize = struct.unpack_from("<I", hdr, 12)[0]
    if not 92 <= hsize <= 512:
        stop(f"GPT header size {hsize} is invalid.")
    h = bytearray(hdr[:hsize])
    crc = struct.unpack_from("<I", h, 16)[0]
    h[16:20] = bytes(4)
    if zlib.crc32(bytes(h)) & 0xFFFFFFFF != crc:
        stop("GPT header CRC is wrong. Do not flash.")
    entries_lba, n, esize, ecrc = struct.unpack_from("<QIII", hdr, 72)
    start = entries_lba * 512
    if esize < 128 or start + n * esize > len(a):
        stop("GPT partition array does not fit in the readback. Do not flash.")
    arr = a[start:start + n * esize]
    if zlib.crc32(arr) & 0xFFFFFFFF != ecrc:
        stop("GPT partition array CRC is wrong. Do not flash.")
    parts = {}
    print(f"{'#':>3} {'name':<16} {'start':>12} {'length':>12}")
    for i in range(n):
        e = arr[i * esize:(i + 1) * esize]
        if e[:16] == bytes(16):
            continue
        first, last = struct.unpack_from("<QQ", e, 32)
        name = e[56:128].decode("utf-16-le", "replace").split("\0")[0]
        off, ln = first * 512, (last - first + 1) * 512
        parts[name] = (off, ln)
        print(f"{i + 1:>3} {name:<16} 0x{off:010x} 0x{ln:010x}")
    for name, want in (("boot", boot_off), ("recovery", rec_off)):
        if name not in parts:
            stop(f"No '{name}' partition in the GPT. Do not flash.")
        off, ln = parts[name]
        if off != want or ln != size:
            stop(f"GPT places '{name}' at 0x{off:x} ({ln} bytes), the script uses 0x{want:x} ({size} bytes). Do not flash.")
    print(f"OK: GPT valid (header and array CRC), read twice identically; boot at 0x{boot_off:x} and recovery at 0x{rec_off:x}, {size} bytes each.")


def main(argv):
    try:
        if len(argv) == 2 and argv[0] == "kernel-version":
            return cmd_kernel_version(argv[1])
        if len(argv) == 3 and argv[0] == "same-kernel":
            return cmd_same_kernel(argv[1], argv[2])
        if len(argv) == 4 and argv[0] == "own-twrp":
            return cmd_own_twrp(argv[1], argv[2], argv[3])
        if len(argv) == 6 and argv[0] == "gpt":
            return cmd_gpt(argv[1], argv[2], int(argv[3], 0), int(argv[4], 0), int(argv[5], 0))
    except OSError as e:
        stop(str(e))
    print(__doc__ or "usage: see the header of this file")
    sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1:])
