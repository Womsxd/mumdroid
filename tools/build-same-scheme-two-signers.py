#!/usr/bin/env python3
"""
Builds the "two signers inside ONE signing scheme" forgery.

  honest-v2-only.apk : signed v2-only with the DEVELOPER key
  attacker-v3-only.apk: signed v3-only with the ATTACKER key

  out.apk : the developer APK's contents, with the v2 pair REPLACED by a pair
            that carries TWO signers -- one per certificate. Both certificates
            are therefore "trusted" in the test's whitelist, so the per-signer
            whitelist check alone cannot reject it. Only the
            "one scheme must not carry two certificates" rule can.

The APK v2/v3 signer list is a repeated field, so a single scheme can legally
hold several signers. A verifier that only asks "is every signer trusted?"
therefore needs the extra per-scheme consistency rule to stay sound.

Signer layout (all little-endian), matching apksig:

    value          = u32 len + SEQUENCE
    SEQUENCE       = signer*
    signer         = u32 len + signedData + signatures + u32 len + publicKey
    signedData     = u32 len + (u32 len + digests) + (u32 len + certificates)
                     + (u32 len + additionalAttributes)
    certificates   = certificate*
    certificate    = u32 len + DER

The certificates are copied verbatim from the two fixture APKs, so their
digests stay stable and the tests can hard-code them.
"""
import struct
import sys

MAGIC = b"APK Sig Block 42"
ID_V2 = 0x7109871A
ID_V3 = 0xF05368C0


def wrap(b):
    return struct.pack("<I", len(b)) + b


def read_block(apk):
    eocd = apk.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("no End-Of-Central-Directory record")
    (cdo,) = struct.unpack_from("<I", apk, eocd + 16)
    magic_offset = cdo - len(MAGIC)
    if apk[magic_offset:magic_offset + len(MAGIC)] != MAGIC:
        raise SystemExit("no APK Signing Block magic")
    (size,) = struct.unpack_from("<Q", apk, magic_offset - 8)
    start = magic_offset + 8 - size
    return start, cdo


def read_pairs(apk, start, end):
    pos, stop = start + 8, end - len(MAGIC) - 8
    out = []
    while pos < stop:
        (length,) = struct.unpack_from("<Q", apk, pos)
        # `length` covers the 4-byte id too, so the value is length - 4 bytes.
        out.append((struct.unpack_from("<I", apk, pos + 8)[0],
                    apk[pos + 12:pos + 8 + length]))
        pos += 8 + length
    return out


def read_raw(buf, pos):
    (length,) = struct.unpack_from("<I", buf, pos)
    return buf[pos + 4:pos + 4 + length], pos + 4 + length


def read_certificates(pair_value):
    """Return the DER certificates stored in a v2/v3 signers pair value.

    Mirrors the verifier: `readLengthPrefixed()` once to get the signer
    sequence, once per signer, once for signedData, once for the digests and
    once for the certificates field -- then once more per certificate.
    """
    signers, _ = read_raw(pair_value, 0)
    certs, p = [], 0
    while p < len(signers):
        signer, p = read_raw(signers, p)
        signed_data, _ = read_raw(signer, 0)
        _, q = read_raw(signed_data, 0)              # digests
        cert_blob, q = read_raw(signed_data, q)      # certificates (a blob)
        r = 0
        while r < len(cert_blob):
            der, r = read_raw(cert_blob, r)          # one wrapped certificate
            certs.append(der)
    return certs


def build_signer(certificates):
    """One signer carrying the given DER certificates.

    Nesting mirrors what `apksigner` emits, verified byte-by-byte against the
    checked-in `honest-v2-only.apk` fixture:

        certificates blob = wrap(concat(wrap(DER)))   -- wrapped per cert + field
        signedData        = wrap(digests) + certificates blob + wrap(b"") + wrap(b"")
        signer            = wrap(signedData)

    The digests sequence is left empty: the parser only reads the certificates,
    and an empty sequence keeps the fixture small while staying well-formed.
    """
    digests = wrap(b"")
    certificates_blob = wrap(b"".join(wrap(c) for c in certificates))
    signed_data = wrap(digests) + certificates_blob + wrap(b"") + wrap(b"")
    return wrap(signed_data)


def build_block(pairs):
    body = b"".join(struct.pack("<Q", len(v) + 4) + struct.pack("<I", i) + v
                    for i, v in pairs)
    size = len(body) + 24
    return struct.pack("<Q", size) + body + struct.pack("<Q", size) + MAGIC


def fix_central_dir_offset(apk, delta):
    eocd = apk.rfind(b"PK\x05\x06")
    (offset,) = struct.unpack_from("<I", apk, eocd + 16)
    return apk[:eocd + 16] + struct.pack("<I", offset + delta) + apk[eocd + 20:]


def main(honest_path, attacker_path, out_path):
    honest = open(honest_path, "rb").read()
    attacker = open(attacker_path, "rb").read()

    s, e = read_block(honest)
    dev_certs = [c for i, v in read_pairs(honest, s, e) if i == ID_V2
                 for c in read_certificates(v)]
    a_s, a_e = read_block(attacker)
    atk_certs = [c for i, v in read_pairs(attacker, a_s, a_e) if i == ID_V3
                 for c in read_certificates(v)]

    # One v2 pair whose SEQUENCE holds two signers (one per certificate):
    #   value = wrap(SEQUENCE), SEQUENCE = signer*  -- each signer is wrapped.
    sequence = wrap(build_signer(dev_certs)) + wrap(build_signer(atk_certs))
    value = wrap(sequence)

    start, end = read_block(honest)
    block = build_block([(ID_V2, value)])
    out = honest[:start] + block + honest[end:]
    out = fix_central_dir_offset(out, len(block) - (end - start))

    open(out_path, "wb").write(out)
    print("wrote %s (%d bytes, %d signers in one scheme)"
          % (out_path, len(out), len(dev_certs) + len(atk_certs)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
