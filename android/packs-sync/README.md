# android/packs-sync

Language-pack updates (plan A2.3): check, download, verify, atomic swap. Android library, used by `:app` (the WorkManager job is
`app/.../packs/PackUpdateWorker`). It is the only module with pack network code; `:ime` stays network-free and shares only files with
it (`ime.packs.PackJournal`, `PackFiles`; see `android/ime/README.md`).

## One check (`PackUpdater.runOnce`)
1. One run at a time (JVM lock + file lock `packs/update/lock`); a concurrent call returns `BUSY`. Unfinished swaps from a dead process are undone; stale `.dl-*.tmp` files are removed.
2. `GET /resource-signing-keys`: key ids with status REVOKED are cached (`update/revoked-keys.txt`); if the call fails the cache is used.
3. `GET /language-resources` per enabled language (platform ANDROID, channel, app version; paged). `UpdatePlanner` keeps, per slot (language, type, platform), the candidates newer than the version floor, newest first, and drops deltas (`baseResourceId`; the core only verifies them), `minAppVersion` above the app, other platforms and languages, blacklisted (rolled back) versions, and slots with an unconfirmed swap. Rollout and channel filtering is the server's.
4. Download (`HttpArtifactDownloader`, its own OkHttp client: no bearer or client headers go to the artifact host; https only, plain http only for the local-stack hosts) to `installed/.dl-<uuid>.tmp`, capped by `containerSizeBytes` (else 64 MiB), after a free-space check. No resume.
5. Verify on a throw-away core engine (`PackVerifier`, the `load_pack` path of A1.6): signature, size and SHA-256 inside the container. Trust is the build-time key list only; a server-announced key is never trusted, but a key the server marks REVOKED (and the build trusts) is verified as REVOKED. The signed manifest must equal the discovery entry (id, version, slot, hash, size, key id), be a non-delta, payload-usable pack, newer than the version floor, and within `minAppVersion`.
6. Swap: journal entry (staged) -> current pack to `.prev` -> temp over `upd-<lang>-<type>-<platform>.pack` -> activate -> `reload.flag`. A failed second rename undoes the first.
7. Candidates that fail verification are not fetched again for 6 h (`update/cooldown.txt`); rolled-back ones are blacklisted for good by the journal.

Only failure codes (`FailReason`), file names and resource ids are logged; pack contents never are.

## Revoked keys quarantine installed packs (ADR 0007 D34)
At the start of each check, before discovery, a key the server lists as REVOKED **and the build trusts** (or one in the cached list when the call fails) condemns the
packs it already signed: every installed `.pack` is read with the throw-away verifier (build trust, without the server revocation, so it still verifies) and, if its signed
manifest names a condemned key, `PackJournal.quarantine` runs:
- the file moves to `<packs>/quarantine/<name>.<epochMs>` (outside the installed directory, so the IME cannot see it; newest 8 kept for diagnosis), its `.prev` is deleted
  (so an installed older pack never silently takes over) and any pending-swap entry is dropped: **the slot falls back to the bundled pack**, which is immutable and never touched;
- the resource id + version is blacklisted (never downloaded again) and `reload.flag` changes, so the IME reloads on the next `onStartInputView`;
- **version floor:** the slot's floor is removed (a floor raised by a pack from a revoked key must not lock out its legitimate replacement; a hostile key could otherwise
  poison it with a huge version). The bundled pack raises it again when the IME loads. Until then the slot is **held** (`update/quarantine-hold.txt`, released when the
  floor is back or after 48 h) so no candidate is judged against "no floor": a replacement must be signed by a trusted, non-revoked key, newer than the bundled pack
  (rule: floor = highest version ever accepted, bundled packs exempt), and arrives on a later run.
Packs signed by other trusted keys stay; a revoked key the build does not trust condemns nothing (the server can tighten trust only for keys the build already knows).
`UpdateReport.quarantined` lists the file names. Test: `QuarantineTest`.

## Tests (`TamperTest`, JVM, real core via JNA, MockWebServer)
Valid update installs, swaps, loads and is confirmed; tampered artifact bytes (SHA-256) and a flipped manifest byte; discovery hash that
disagrees with the signed manifest; valid SHA with a bad signature; unknown key (even if the server lists it); revoked key (and the cached
revocation when the key list is unreachable); downgrade (floor) and a server that lies about the version; delta and too-new `minAppVersion`
skipped without downloading; truncated body (short Content-Length and a dropped connection); body larger than announced; remote plain http;
a pack that fails to load in the IME is rolled back to `.prev` and blacklisted; repeated unconfirmed starts roll back and restore the floor;
rollback to the bundled pack when there is no `.prev`; an interrupted swap is undone; `QuarantineTest` (D34: revoked key quarantines installed packs, offline cache, untrusted key and other keys unaffected, hold then replacement, unconfirmed swap + `.prev`); disk full while writing and not enough free space leave
the old pack intact; two concurrent checks leave consistent state. Test keys only; the trust list is injected.
`./gradlew :packs-sync:testDebugUnitTest` (from `android/`). `TestPacks.kt` duplicates `:ime`'s test signer (no shared test fixtures yet).

## Needs a device
WorkManager scheduling and constraints (Wi-Fi, battery, storage) on real devices; the IME reloading on `onStartInputView` and the first-show
confirmation (`KeyboardImeService` is not unit-testable); a native crash on first use rolling back across a real process death; real CDN
signed URLs and redirects.
