# ADR 0001 — Open items register

**Status:** Living register. All 24 items are OPEN at creation.
**Date:** 2026-11-17
**Context:** Plan step F0.5. Records the decisions that humans must make, taken from System Design §26.2, Architecture Review §6 (open risks) and items found during Phase 0. Coding agents need this list so they know what is still undecided.

**How to use:**
- Humans resolve items. Coding agents must not guess an answer for an OPEN item. Use config or placeholders and flag the item.
- When an item is resolved, set Status to RESOLVED and record the decision and date in Decision / notes.
- If a decision changes the design, update the design docs and write a new ADR numbered 0002 or later.

| ID | Item | Source | Owner | Due milestone | Blocks plan steps | Status | Decision / notes |
|---|---|---|---|---|---|---|---|
| OI-01 | Final launch-language set and dialect coverage (incl. Tigrinya Ethiopia vs Eritrea conventions) | SD §26.2 | Product + language leads | M0 | L1.3 | OPEN | — |
| OI-02 | Approved AM/TI phonetic schemes and onboarding default (phonetic vs Fidel) | SD §26.2 | Language panels | M0 | C1.4 | OPEN | Plan gate G0 |
| OI-03 | Initial MT/LLM/ASR/TTS providers per pair, with ZDR contracts | SD §26.2 | AI lead | M0 | B5.1, F0.6 | OPEN | Plan gate G0 (provider shortlist) |
| OI-04 | Hosting region and transfer basis under the Ethiopian PDP Proclamation | SD §26.2 | Legal + infra | M0 | H3.4 | OPEN | — |
| OI-05 | Final quotas and ETB/GBP/USD/EUR price points after cost modelling | SD §26.2 | Product + finance | M3 | B4.1, G4 | OPEN | Plan gate G4 (quota/cost model) |
| OI-06 | Play Billing buyer availability for Ethiopian accounts; need for `direct` flavour | SD §26.2 | Product + legal | M3 | M4.7, M4.8 | OPEN | Plan gate G3 |
| OI-07 | Apple storefront purchase path for Ethiopian users | SD §26.2 | Product + legal | M5 | I5.5 | OPEN | — |
| OI-08 | Chapa merchant onboarding, methods, limits, settlement, refunds | SD §26.2 | Finance | M4 | M4.2 | OPEN | — |
| OI-09 | ArifPay recurring and Telebirr mandate approval for target methods | SD §26.2 | Finance | M4+ | M4.9 | OPEN | — |
| OI-10 | VAT/receipt obligations in Ethiopia and diaspora markets | SD §26.2 | Finance | M4 | M4.1, M4.3 | OPEN | — |
| OI-11 | SMS/OTP aggregator for +251 and cost per message | SD §26.2 | Engineering | M2 | B2.1 | OPEN | — |
| OI-12 | Rust capacity (or D2 fallback) | SD §26.2 | Engineering manager | M0 | C1.1 | OPEN | — |
| OI-R1 | Amharic/Tigrinya/Oromo MT and ASR quality from commercial providers is uneven | AR §6 | AI lead | TBD | F0.6 | OPEN | Mitigation: evaluation sets per pair, `quality_tier` gating, native-speaker panels before marketing a pair |
| OI-R2 | Google Play buyer availability for Ethiopian accounts and Apple storefront options | AR §6 | Product + legal | TBD | M4.7, M4.8 | OPEN | Mitigation: market/channel policy table fails closed; `direct` Android flavour |
| OI-R3 | Recurring local payments (Telebirr mandate, ArifPay recurring) need provider approval | AR §6 | Finance | TBD | M4.9 | OPEN | Mitigation: MANUAL_RENEW default; mandates only when approved |
| OI-R4 | Regional internet shutdowns and low-bandwidth networks | AR §6 | Engineering | TBD | | OPEN | Mitigation: offline-first typing, small delta packs, short AI timeouts, graceful offline UX |
| OI-R5 | Low-end devices (2 GB RAM, Android Go) | AR §6 | Mobile lead | TBD | | OPEN | Mitigation: memory and latency budgets (SD §17.1); on-device AI gated by capability |
| OI-R6 | Rust capacity in the team | AR §6 | Engineering manager | TBD | C1.1 | OPEN | Mitigation: D2 fallback path (same item as OI-12) |
| OI-R7 | Model and dataset licensing (non-commercial open models) | AR §6 | AI lead + legal | TBD | L1.2, E7.2 | OPEN | Mitigation: `language_datasets` licence gate; release gate R7 |
| OI-P1 | Chapa mock stub shapes in `infra/compose/wiremock/mappings/chapa-*.json` were written from memory; verify against Chapa's official API docs before step M4.2 | Phase 0 finding | Engineering lead | M4 | M4.2 | OPEN | — |
| OI-P2 | MinIO local image is a Chainguard build pinned by digest because `minio/minio` is no longer published; decide on long-term S3-compatible dev store and repin policy | Phase 0 finding | Engineering lead | M2 | | OPEN | — |
| OI-P3 | Spring Boot application class lives in package `com.qalsmartkeyboard.api`; component scanning will miss sibling module packages. Move it to `com.qalsmartkeyboard` in step B1.1 | Phase 0 finding | Engineering lead | M2 | B1.1 | CLOSED | Moved in B1.1 (2d83e78) |
| OI-P4 | Local machine has JDK 21 only; backend targets Java 25 (CI uses 25). Install JDK 25 locally | Phase 0 finding | Engineering lead | M0 | | OPEN | — |
| OI-P5 | AI mock request/response shapes in `infra/compose/wiremock/mappings/ai-*.json` are placeholders until providers are chosen | Phase 0 finding | Engineering lead | M2 | B5.1 | OPEN | Plan gate G0 |
| OI-P6 | Crypto-shredding and backups: SD §19.4 says shredding covers backup copies, but a backup taken before the shred still holds the wrapped DEK. Needs a KEK-version destruction policy and backup retention aligned with it | B1.5 review | Security lead | M3 | B6.2 | DECIDED | ADR 0007 D1 (30-day backup cap, KEK version destruction; implement with cloud KMS before launch) |
| OI-P7 | Dictionary `word_hmac` (Review F17) must not be keyed by the rotating per-user DEK, or the (user, language, hmac) unique index breaks after rotation. Define a stable per-user HMAC key lifecycle | B1.5 review | Backend lead | M2 | B3.6 | CLOSED | Per-user dictionary HMAC key built in B3.6 (ADR 0018) |
| OI-P8 | `user_data_keys` ownership: SD §20.2 puts it in Identity, code lives in `platform.crypto` (crypto-shred also listed under `privacy`). Table has no column for wrap algorithm or KEK version (carried in `kms_key_id` + wrapped format byte); row `key_version` duplicates the ciphertext header unchecked | B1.5 review | Backend lead | M2 | B6.2 | DECIDED | ADR 0007 D3 (platform.crypto owns the table) |
| OI-P9 | Flyway V2+ migrations (from B1.4 on) live only in `backend/`; the canonical DB design in docs and `contracts/sync.sh` cover V1 only. Decide whether docs list later migrations | B1.4 review | Engineering lead | M2 | CLOSED | ADR 0007 D4 (docs SQL = V1 baseline; V2+ canonical in backend) |
| OI-P10 | Contract declares `APP_VERSION_UNSUPPORTED` (426) only in the Conventions text: no shared 426 response, none on any operation, and no 400 for malformed client headers. Add `components.responses` entries (and decide per-operation declaration) in the docs copy, then `contracts/sync.sh` | B1.6 review | API owner | M2 | CLOSED | ADR 0007 D5 (shared AppVersionUnsupported response added) |
| OI-P11 | No `SecurityFilterChain` yet: Spring's default security answers 401 before anonymous endpoints (`/config`, OTP start) run in the real app. Baseline chain with explicit anonymous paths needed with auth | B1.3/B1.6 review | Backend lead | M2 | B2.4 | CLOSED | Baseline chain in B2.4 (b4097c8) |
