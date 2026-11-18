# ADR 0016 — Android transport security: no API certificate pinning

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** A2.1 (depends on B1.3) · **Spec:** SD §18 (Transport: TLS
everywhere, HSTS, no plaintext fallback; certificate pinning is not used for the API, pack signature keys are pinned),
SD §7.6 (pack signing keys), SD §9 (sessions); ADR 0002 (signed packs), ADR 0006 (tokens), ADR 0007 D8 (refresh)

## Context
Plan step A2.1 asks for a certificate pinning decision per SD §18. SD §18 already states the position: the API is **not**
pinned because of operational risk, while pack signature keys are. The risk is concrete for this product: the backend
sits behind a managed edge whose certificates and intermediates rotate outside our control; a wrong or stale pin bricks
every installed copy of an app that ships through stores (days of review) and sideloaded APKs (no update channel), and
the IME is the user's keyboard, so a bricked network layer must never spill into typing (it does not, typing is
offline) but would still lock users out of sign-in, sync and AI until an update lands. The assets the API protects are
session tokens and user text, both short-lived on the wire; the realistic threats are a hostile network and a user-added
or rogue CA.

## Decision
1. **No certificate or public-key pinning for the API in production.** This confirms SD §18; no pin set, backup pins,
   expiry or remote kill switch is shipped, because a kill switch would itself be fetched over the channel being pinned.
2. **Network Security Config in `:app`** (`res/xml/network_security_config.xml`, referenced from the manifest):
   `cleartextTrafficPermitted="false"` and **system trust anchors only** (user-installed CAs are not trusted by the
   app). This is the mitigation for the "user-added CA" threat that pinning would otherwise cover on a cooperative
   device.
3. **Debug builds only** (`src/debug/res/xml/network_security_config.xml`) additionally allow cleartext to `10.0.2.2`,
   `localhost` and `127.0.0.1`, for the local stack. Release builds have no cleartext exception.
4. **Client-side guard:** `:api` `TransportConfig` rejects an `http://` base URL unless the host is one of those
   loopback hosts.
5. **Optional pinning hook, off.** `TransportConfig.certificatePins` (host pattern to `sha256/` SPKI pins, OkHttp
   `CertificatePinner`) exists so that reversing this decision needs a configuration change, not new code. It is empty
   in every build and ignored for loopback hosts. Turning it on requires a new ADR that supplies: at least two pins
   (current and backup, from different keys), a pin set that includes the CA/intermediate rather than a leaf, an expiry
   after which the pin is ignored, and a rollout that cannot strand sideloaded `direct` installs.
6. **Out of scope here and unchanged:** pack signature keys stay pinned in the app (SD §7.6, ADR 0002); Certificate
   Transparency enforcement is left to the platform defaults and is not configured by the app.

## Consequences
- A compromised CA (or a device whose owner installed a system-level CA) could still intercept API traffic. Accepted:
  tokens are short-lived, refresh rotation with reuse detection (ADR 0006, ADR 0007 D8) limits replay, the content
  endpoints require an explicit user action, and pack integrity does not depend on TLS.
- No operational pin rotation runbook is needed.
- Revisit if the threat model changes (for example a partner contract or a regulator requiring pinning) or after a
  real interception incident.
