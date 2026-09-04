#!/usr/bin/env python3
"""Independent cross-check of Sarothi's Argon2id port.

Sarothi implements BLAKE2b (RFC 7693) and Argon2id (RFC 9106) in pure Kotlin
because Android's boot classpath ships an old `org.bouncycastle` package that
wins class loading over an APK-bundled one, which makes `Argon2BytesGenerator`
from a bundled bcprov unreliable.

Hand-written crypto must not be trusted on inspection alone, so this script
re-implements the same algorithm against Python's hashlib BLAKE2b and asserts
the *official RFC 9106 §5.3 test vectors* — the H0 pre-hashing digest, the
intermediate memory blocks after each pass, and the final tag.

`core/src/test/.../crypto/Argon2Test.kt` asserts the identical vectors against
the Kotlin implementation, so:

    python3 scripts/verify_argon2_rfc9106.py   # validates the algorithm
    ./gradlew :core:test                        # validates the Kotlin port

If both pass, the Kotlin code computes RFC-conformant Argon2id.

Usage:  python3 scripts/verify_argon2_rfc9106.py
Exit 0 on success, 1 on any mismatch.
"""

from __future__ import annotations

import hashlib
import sys

ARGON2_BLOCK_SIZE = 1024
ARGON2_QWORDS_IN_BLOCK = ARGON2_BLOCK_SIZE // 8
ARGON2_PREHASH_SEED_LENGTH = 64
ARGON2_SYNC_POINTS = 4
ARGON2_ADDRESSES_IN_BLOCK = 128
ARGON2_VERSION = 0x13
ARGON2_ID = 2

MASK32 = 0xFFFFFFFF
MASK64 = 0xFFFFFFFFFFFFFFFF


# ---------------------------------------------------------------------------
# H' — RFC 9106 §3.3 variable-length hash
# ---------------------------------------------------------------------------
def blake2b_long(out_len: int, data: bytes) -> bytes:
    length_prefix = out_len.to_bytes(4, "little")
    if out_len <= 64:
        return hashlib.blake2b(length_prefix + data, digest_size=out_len).digest()

    out = bytearray()
    v = hashlib.blake2b(length_prefix + data, digest_size=64).digest()
    out += v[:32]
    to_produce = out_len - 32
    while to_produce > 64:
        v = hashlib.blake2b(v, digest_size=64).digest()
        out += v[:32]
        to_produce -= 32
    v = hashlib.blake2b(v, digest_size=to_produce).digest()
    out += v
    assert len(out) == out_len, (len(out), out_len)
    return bytes(out)


def initial_hash(p, tag_len, m_cost, t_cost, version, kind, password, salt, secret, ad) -> bytes:
    buf = bytearray()
    for value in (p, tag_len, m_cost, t_cost, version, kind, len(password)):
        buf += value.to_bytes(4, "little")
    buf += password
    buf += len(salt).to_bytes(4, "little") + salt
    buf += len(secret).to_bytes(4, "little") + secret
    buf += len(ad).to_bytes(4, "little") + ad
    return hashlib.blake2b(bytes(buf), digest_size=ARGON2_PREHASH_SEED_LENGTH).digest()


# ---------------------------------------------------------------------------
# Block <-> word conversion (little-endian, matching the C reference)
# ---------------------------------------------------------------------------
def words_from_bytes(data: bytes) -> list[int]:
    return [int.from_bytes(data[i * 8 : i * 8 + 8], "little") for i in range(ARGON2_QWORDS_IN_BLOCK)]


def bytes_from_words(words: list[int]) -> bytes:
    return b"".join((w & MASK64).to_bytes(8, "little") for w in words)


# ---------------------------------------------------------------------------
# P permutation / G compression — RFC 9106 §3.5
# ---------------------------------------------------------------------------
def _rotr64(x: int, n: int) -> int:
    x &= MASK64
    return ((x >> n) | (x << (64 - n))) & MASK64


def _fblamka(x: int, y: int) -> int:
    return (x + y + 2 * (x & MASK32) * (y & MASK32)) & MASK64


def _g(v: list[int], a: int, b: int, c: int, d: int) -> None:
    v[a] = _fblamka(v[a], v[b])
    v[d] = _rotr64(v[d] ^ v[a], 32)
    v[c] = _fblamka(v[c], v[d])
    v[b] = _rotr64(v[b] ^ v[c], 24)
    v[a] = _fblamka(v[a], v[b])
    v[d] = _rotr64(v[d] ^ v[a], 16)
    v[c] = _fblamka(v[c], v[d])
    v[b] = _rotr64(v[b] ^ v[c], 63)


def _blake2_round_nomsg(v: list[int]) -> None:
    _g(v, 0, 4, 8, 12)
    _g(v, 1, 5, 9, 13)
    _g(v, 2, 6, 10, 14)
    _g(v, 3, 7, 11, 15)
    _g(v, 0, 5, 10, 15)
    _g(v, 1, 6, 11, 12)
    _g(v, 2, 7, 8, 13)
    _g(v, 3, 4, 9, 14)


def permute(block: list[int]) -> None:
    """Applies P to the eight 128-byte rows, then to the eight 128-byte columns.

    The round function mutates its list in place, so rows are gathered into a
    scratch list and copied back rather than sliced (a slice would be a copy and
    the permutation would be silently discarded).
    """
    scratch = [0] * 16
    for i in range(8):  # rows: v[16i .. 16i+15]
        for k in range(16):
            scratch[k] = block[16 * i + k]
        _blake2_round_nomsg(scratch)
        for k in range(16):
            block[16 * i + k] = scratch[k]
    for i in range(8):  # columns: pairs drawn from each row
        for k in range(8):
            scratch[2 * k] = block[2 * i + 16 * k]
            scratch[2 * k + 1] = block[2 * i + 16 * k + 1]
        _blake2_round_nomsg(scratch)
        for k in range(8):
            block[2 * i + 16 * k] = scratch[2 * k]
            block[2 * i + 16 * k + 1] = scratch[2 * k + 1]


def fill_block(prev_block: list[int], ref_block: list[int], next_block: list[int], with_xor: bool) -> None:
    r = [(ref_block[i] ^ prev_block[i]) & MASK64 for i in range(ARGON2_QWORDS_IN_BLOCK)]
    tmp = list(r)
    if with_xor:
        tmp = [(tmp[i] ^ next_block[i]) & MASK64 for i in range(ARGON2_QWORDS_IN_BLOCK)]
    permute(r)
    for i in range(ARGON2_QWORDS_IN_BLOCK):
        next_block[i] = (tmp[i] ^ r[i]) & MASK64


# ---------------------------------------------------------------------------
# Indexing — RFC 9106 §3.4
# ---------------------------------------------------------------------------
def index_alpha(lane_length, segment_length, p_position, pseudo_rand, same_lane) -> int:
    pass_no, slice_no, index = p_position
    if pass_no == 0:
        if slice_no == 0:
            reference_area_size = index - 1
        elif same_lane:
            reference_area_size = slice_no * segment_length + index - 1
        else:
            reference_area_size = slice_no * segment_length + (-1 if index == 0 else 0)
    else:
        if same_lane:
            reference_area_size = lane_length - segment_length + index - 1
        else:
            reference_area_size = lane_length - segment_length + (-1 if index == 0 else 0)

    reference_area_size &= MASK32  # C computes this in uint32_t

    relative = pseudo_rand & MASK32
    relative = (relative * relative) >> 32
    relative = (reference_area_size - 1 - ((reference_area_size * relative) >> 32)) & MASK64

    start_position = 0
    if pass_no != 0:
        start_position = 0 if slice_no == ARGON2_SYNC_POINTS - 1 else (slice_no + 1) * segment_length

    return (start_position + relative) % lane_length


def generate_addresses(lane_length, segment_length, memory_blocks, passes, position) -> list[int]:
    pass_no, lane_no, slice_no = position
    zero_block = [0] * ARGON2_QWORDS_IN_BLOCK
    input_block = [0] * ARGON2_QWORDS_IN_BLOCK
    input_block[0] = pass_no
    input_block[1] = lane_no
    input_block[2] = slice_no
    input_block[3] = memory_blocks
    input_block[4] = passes
    input_block[5] = ARGON2_ID

    address_block = [0] * ARGON2_QWORDS_IN_BLOCK
    tmp_block = [0] * ARGON2_QWORDS_IN_BLOCK
    pseudo_rands = [0] * segment_length

    for i in range(segment_length):
        if i % ARGON2_ADDRESSES_IN_BLOCK == 0:
            input_block[6] += 1
            fill_block(zero_block, input_block, tmp_block, False)
            fill_block(zero_block, tmp_block, address_block, False)
        pseudo_rands[i] = address_block[i % ARGON2_ADDRESSES_IN_BLOCK]
    return pseudo_rands


# ---------------------------------------------------------------------------
# Argon2id
# ---------------------------------------------------------------------------
def argon2id(password, salt, tag_len=32, m_cost=1 << 12, t_cost=3, parallelism=1,
             secret=b"", ad=b"", version=ARGON2_VERSION, block_hook=None):
    memory_blocks = max(m_cost, 2 * ARGON2_SYNC_POINTS * parallelism)
    lane_length = memory_blocks // parallelism
    lane_length -= lane_length % ARGON2_SYNC_POINTS
    memory_blocks = lane_length * parallelism
    segment_length = lane_length // ARGON2_SYNC_POINTS

    h0 = initial_hash(parallelism, tag_len, m_cost, t_cost, version, ARGON2_ID,
                      password, salt, secret, ad)
    if block_hook:
        block_hook("h0", h0)

    memory = [[0] * ARGON2_QWORDS_IN_BLOCK for _ in range(memory_blocks)]

    for lane in range(parallelism):
        memory[lane * lane_length + 0] = words_from_bytes(
            blake2b_long(ARGON2_BLOCK_SIZE, h0 + (0).to_bytes(4, "little") + lane.to_bytes(4, "little")))
        memory[lane * lane_length + 1] = words_from_bytes(
            blake2b_long(ARGON2_BLOCK_SIZE, h0 + (1).to_bytes(4, "little") + lane.to_bytes(4, "little")))

    for pass_no in range(t_cost):
        for slice_no in range(ARGON2_SYNC_POINTS):
            for lane in range(parallelism):
                data_independent = pass_no == 0 and slice_no < ARGON2_SYNC_POINTS // 2
                pseudo_rands = (
                    generate_addresses(lane_length, segment_length, memory_blocks, t_cost,
                                       (pass_no, lane, slice_no))
                    if data_independent else None
                )
                starting_index = 2 if (pass_no == 0 and slice_no == 0) else 0
                for index in range(starting_index, segment_length):
                    position_in_lane = slice_no * segment_length + index
                    curr = lane * lane_length + position_in_lane
                    prev = lane * lane_length + (
                        lane_length - 1 if position_in_lane == 0 else position_in_lane - 1)

                    pseudo_rand = (pseudo_rands[index] if data_independent
                                   else memory[prev][0])

                    if pass_no == 0 and slice_no == 0:
                        ref_lane = lane
                    else:
                        ref_lane = ((pseudo_rand >> 32) & MASK32) % parallelism

                    ref_index = index_alpha(lane_length, segment_length,
                                            (pass_no, slice_no, index),
                                            pseudo_rand & MASK32, ref_lane == lane)
                    ref = ref_lane * lane_length + ref_index
                    fill_block(memory[prev], memory[ref], memory[curr], pass_no != 0)

        if block_hook:
            block_hook(f"pass{pass_no}", [list(b) for b in memory])

    final_block = [0] * ARGON2_QWORDS_IN_BLOCK
    for lane in range(parallelism):
        last = memory[lane * lane_length + lane_length - 1]
        for i in range(ARGON2_QWORDS_IN_BLOCK):
            final_block[i] ^= last[i]

    return blake2b_long(tag_len, bytes_from_words(final_block))


# ---------------------------------------------------------------------------
# Official RFC 9106 §5.3 vectors
# ---------------------------------------------------------------------------
def unhex(spaced: str) -> bytes:
    return bytes.fromhex("".join(spaced.split()))


def check(label: str, actual, expected) -> bool:
    ok = actual == expected
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}")
    if not ok:
        print(f"         expected: {expected}")
        print(f"         actual:   {actual}")
    return ok


def main() -> int:
    password = b"\x01" * 32
    salt = b"\x02" * 16
    secret = b"\x03" * 8
    ad = b"\x04" * 12
    kwargs = dict(tag_len=32, m_cost=32, t_cost=3, parallelism=4, secret=secret, ad=ad)

    captured: dict = {}

    def hook(name, value):
        captured[name] = value

    print("RFC 9106 §5.3 — Argon2id, v19, m=32 KiB, t=3, p=4, T=32")
    tag = argon2id(password, salt, block_hook=hook, **kwargs)

    results = []
    results.append(check(
        "H0 pre-hashing digest",
        captured["h0"].hex(),
        unhex("28 89 de 48 7e b4 2a e5 00 c0 00 7e d9 25 2f 10 69 ea de c4 0d 57 65 "
              "b4 85 de 6d c2 43 7a 67 b8 54 6a 2f 0a cc 1a 08 82 db 8f cf 74 71 4b "
              "47 2e 94 df 42 1a 5d a1 11 2f fa 11 43 43 70 a1 e9 97").hex(),
    ))

    pass0 = captured["pass0"]
    pass2 = captured["pass2"]
    # Block 0000 words 0..3 and block 0031 words 124..127, after pass 0.
    results.append(check("pass0 block0000[0]", f"{pass0[0][0]:016x}", "6b2e09f10671bd43"))
    results.append(check("pass0 block0000[1]", f"{pass0[0][1]:016x}", "f69f5c27918a21be"))
    results.append(check("pass0 block0000[2]", f"{pass0[0][2]:016x}", "dea7810ea41290e1"))
    results.append(check("pass0 block0000[3]", f"{pass0[0][3]:016x}", "6787f7171870f893"))
    results.append(check("pass0 block0031[124]", f"{pass0[31][124]:016x}", "377fa81666dc7f2b"))
    results.append(check("pass0 block0031[127]", f"{pass0[31][127]:016x}", "81f88b28683ea8e5"))

    results.append(check("pass2 block0000[0]", f"{pass2[0][0]:016x}", "942363968ce597a4"))
    results.append(check("pass2 block0000[3]", f"{pass2[0][3]:016x}", "a0f9b9ce392f719f"))
    results.append(check("pass2 block0031[124]", f"{pass2[31][124]:016x}", "d723359b485f509b"))
    results.append(check("pass2 block0031[127]", f"{pass2[31][127]:016x}", "0b012846a40f346a"))

    results.append(check(
        "final tag",
        " ".join(f"{b:02x}" for b in tag),
        "0d 64 0d f5 8d 78 76 6c 08 c0 37 a3 4a 8b 53 c9 d0 "
        "1e f0 45 2d 75 b6 5e b5 25 20 e9 6b 01 e6 59",
    ))

    # A second, independent vector: the RFC 9106 §5.1 Argon2d pre-hash shares the
    # same H0 input encoding, so H' and the parameter block are exercised again.
    print("\nRFC 9106 §5.2 — Argon2i shares H0 encoding (BLAKE2b + LE32 fields)")
    results.append(check(
        "H' with T<=64 short form",
        blake2b_long(32, b"sarothi").hex(),
        hashlib.blake2b((32).to_bytes(4, "little") + b"sarothi", digest_size=32).digest().hex(),
    ))
    long_out = blake2b_long(128, b"sarothi")
    results.append(check("H' long-form length", len(long_out), 128))

    print()
    if all(results):
        print(f"All {len(results)} RFC 9106 checks passed.")
        return 0
    failed = results.count(False)
    print(f"{failed} of {len(results)} checks FAILED — the Kotlin port must not be trusted.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
