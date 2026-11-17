"""Pure-Python Ed25519 (RFC 8032, "pure" mode), stdlib only. DEV TOOLING, NOT CONSTANT-TIME.

Used by build_packs.py so the pack build needs no third-party package. It is deliberately an
independent implementation from the Rust one in `core`: a pack signed here that verifies in the
core is a cross-implementation check (plan L1.1). Never use it with production keys (those live
in KMS/HSM, SD §18); timing side channels are irrelevant for dev keys only.
"""
import hashlib

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, P - 2, P) % P
I = pow(2, (P - 1) // 4, P)
BY = 4 * pow(5, P - 2, P) % P


def _recover_x(y, sign):
    xx = (y * y - 1) * pow(D * y * y + 1, P - 2, P) % P
    x = pow(xx, (P + 3) // 8, P)
    if (x * x - xx) % P != 0:
        x = x * I % P
    if (x * x - xx) % P != 0:
        raise ValueError("not a point")
    if x & 1 != sign:
        x = P - x
    return x


BX = _recover_x(BY, 0)
B = (BX, BY, 1, BX * BY % P)  # extended coordinates (X, Y, Z, T)


def _add(p, q):
    a = (p[1] - p[0]) * (q[1] - q[0]) % P
    b = (p[1] + p[0]) * (q[1] + q[0]) % P
    c = 2 * p[3] * q[3] * D % P
    d = 2 * p[2] * q[2] % P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % P, g * h % P, f * g % P, e * h % P)


def _mul(s, p):
    q = (0, 1, 1, 0)
    while s:
        if s & 1:
            q = _add(q, p)
        p = _add(p, p)
        s >>= 1
    return q


def _encode(p):
    zi = pow(p[2], P - 2, P)
    x, y = p[0] * zi % P, p[1] * zi % P
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def _h(m):
    return int.from_bytes(hashlib.sha512(m).digest(), "little")


def _expand(seed):
    if len(seed) != 32:
        raise ValueError("seed must be 32 bytes")
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def public_key(seed):
    a, _ = _expand(seed)
    return _encode(_mul(a, B))


def sign(seed, message):
    a, prefix = _expand(seed)
    pub = _encode(_mul(a, B))
    r = _h(prefix + message) % L
    rs = _encode(_mul(r, B))
    k = _h(rs + pub + message) % L
    s = (r + k * a) % L
    return rs + s.to_bytes(32, "little")
