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

## Tests (`TamperTest`, JVM, real core via JNA, MockWebServer)
Valid update installs, swaps, loads and is confirmed; tampered artifact bytes (SHA-256) and a flipped manifest byte; discovery hash that
disagrees with the signed manifest; valid SHA with a bad signature; unknown key (even if the server lists it); revoked key (and the cached
revocation when the key list is unreachable); downgrade (floor) and a server that lies about the version; delta and too-new `minAppVersion`
skipped without downloading; truncated body (short Content-Length and a dropped connection); body larger than announced; remote plain http;
a pack that fails to load in the IME is rolled back to `.prev` and blacklisted; repeated unconfirmed starts roll back and restore the floor;
rollback to the bundled pack when there is no `.prev`; an interrupted swap is undone; disk full while writing and not enough free space leave
the old pack intact; two concurrent checks leave consistent state. Test keys only; the trust list is injected.
`./gradlew :packs-sync:testDebugUnitTest` (from `android/`). `TestPacks.kt` duplicates `:ime`'s test signer (no shared test fixtures yet).

## Needs a device
WorkManager scheduling and constraints (Wi-Fi, battery, storage) on real devices; the IME reloading on `onStartInputView` and the first-show
confirmation (`KeyboardImeService` is not unit-testable); a native crash on first use rolling back across a real process death; real CDN
signed URLs and redirects.
