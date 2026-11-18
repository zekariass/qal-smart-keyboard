# android/sync

Settings, destination-profile, app-rule and personal-dictionary sync with the backend (plan step A2.4). An Android library with
JVM tests; the WorkManager job and the settings switch live in `:app` (`app/.../sync`). **Placement:** network code stays out of
`:ime` and `:data` (their `NoNetworkTest` stays green); this module depends on `:data` (settings, `EncryptedFileStore`) and `:api`
(the shared OkHttp client with bearer, refresh and client headers), not on `:ime` or the Rust core. The personal dictionary is
reached through the `LocalDictionary` port that `:app` implements over `UserDictionary`.

Why hand-written JSON instead of the generated models: every document is a full-replace PUT, so members this app does not edit
(`translationMode`, `historyEnabled`, unknown languages ...) must be carried through from the server document untouched.
`SyncHttp` runs the calls over `ApiClients.okHttpClient` and maps failures to the same `ApiError`s (`ApiErrors.fromHttp`).

## Files
| File | Role |
|---|---|
| `SyncEngine.kt` | One run: preferences, typing languages, destination profiles, app rules, dictionary (if switched on). Steps are independent; a signed-out session ends the run; one run at a time (`BUSY`). Reports per-step `StepResult` and `retryable`. |
| `DocSync.kt` | The revision protocol shared by the three revisioned documents (below). |
| `PreferencesSync.kt`, `TypingLanguagesSync.kt` | `/me/preferences`, `/me/typing-languages` (merge rules below); `ProfileDirectory` (transliteration profiles per language, cached 24 h). |
| `DestinationSync.kt`, `DestinationStore.kt` | Destination profiles (last writer wins) and app rules (hashed, revision + merge); the encrypted local store the future editor uses. `RuleKeyStore` holds the per-account key. |
| `DictionarySync.kt` | Dictionary sync, `LocalDictionary` port, `DictionaryConsent` (the settings switch). |
| `SyncState.kt` | Revisions, last server documents (merge bases), rule key, dictionary cursor/snapshot/ids: one JSON blob in `EncryptedFileStore` (AES-256-GCM, Keystore key `qal.sync.v1` in `:app`). Unreadable = a first sync; `reset()` on sign-out. |

## Revision protocol (`DocSync`)
Every PUT is a full replace carrying `revision`; a stale one is 409 `REVISION_CONFLICT`. With a remembered server document the
engine PUTs optimistically with its revision when the merge has something to send, otherwise it GETs to see whether the server
moved. On 409 (or without a remembered document) it GETs, merges again on that document (the merge **base stays the last synced
document**, so "changed since the last sync" is stable across retries) and PUTs with the revision just read. At most 4 PUTs, then
the step fails as retryable `REVISION_CONFLICT` (WorkManager backoff). The answer becomes the next base. No PUT is sent when the
merged document equals the server's.

## Merge rules (field level, three-way; base = last synced document, schema defaults before the first sync)
- **Preferences** (`numeralStyle`, `keyboardTheme` = `ThemeMode` name, `hapticsEnabled`): changed on this device since the last
  sync -> local wins (even if the server changed it too); otherwise the server value wins and is adopted locally; a field the
  server lacks keeps the local value. Every other member is the server's. `keyHeightPercent`, `lastModes`, pack settings are
  device-local and never sent. Server values are applied locally per field only if the user did not edit that field meanwhile.
- **Typing languages**, per language and per field (`enabled`; the pair input mode + profile; conversion trigger; candidates;
  unknown-token behaviour): local change wins, else server. Order: local order if the user reordered since the last sync, else the
  server's; languages the server lacks are appended. Unrepresentable server entries are preserved. `isPrimary` = first enabled,
  `sortOrder` 10, 20 ... (`toSyncItems`).
- **Profile codes (ADR 0007 D21).** The backend code is canonical. A phonetic language sends its stored code if it is an ACTIVE
  profile of the language (or already the one the server holds: a RETIRED profile stays valid); otherwise (null = newest active,
  or a legacy derived code) the newest ACTIVE profile from `GET /languages/{code}/transliteration-profiles`, and the local setting
  then follows the canonical code. **PHONETIC is never sent without a code**: if none can be resolved the language is *deferred*
  (the server's entry is left as is, nothing applied locally, step reported `Deferred`, retried). A 422 `PROFILE_NOT_ACTIVE` drops
  the cached list and retries once.
- **Destination profiles: last writer wins, no revision** (as in the contract). Push first (local deletes, creates, edits; a 404 on
  an edit means deleted elsewhere and the local edit re-creates it), then pull and reconcile: clean profiles take the server's
  version, new ones are added, clean synced ones the server no longer has are removed, still-dirty ones are left alone.
- **App rules** (revision protocol): matched by `appPackageHmac`; per hash, changed locally (added, edited, removed) -> local, else
  server. A rule whose target profile has no server id yet waits (profiles sync first). Cap 200.

## App-rule hashing
`GET /me/app-destination-rules/key` once (one 256-bit key per account, never regenerated; guests may read it), stored in the encrypted
sync state, never logged and never sent anywhere. The device computes `hex(HMAC-SHA256(key, UTF-8 packageName))` at sync time from
`EditorInfo.packageName`-style names kept **only in the encrypted local store**; the wire carries only 64-hex hashes (test asserts no
request body, path or query contains a package name or the key). Rules that arrived from another device have only the hash. When a
sign-in finishes (a guest's key may be replaced by the account's, B3.4) `SyncEngine.signedIn()` drops the key and the rule base and
forgets hash-only rules; known package names are re-hashed with the key read next. Sign-out wipes all sync state and the local profiles/rules.

## Personal dictionary
- **Mapping.** Local user words per language <-> server `ADD` / `DELETE`. A *snapshot* (words known to be on the server after the last
  sync, encrypted state) is diffed against the local words: new = `ADD`, gone = `DELETE`. Each change gets a UUID `clientChangeId`
  stored until acknowledged, so a retry after a lost response or a crash re-sends the same ids (idempotent). NFC; words the server would
  reject (empty, over 64 characters, whitespace/control) are never sent; rejections in an answer are acknowledged.
- **Order.** Push, then pull (`since` cursor = `syncToken` of the last pulled page; a push answer's token is ignored). Last writer wins per
  word in server arrival order, so a local change is the later write. A pulled change for a word with a local change still waiting is skipped.
- **D18.** Pending sightings (typed once, not learned) are not in `UserDictionary.words()` and never sync; bigrams are local only. Remote
  words are applied with the explicit `add` (learned at once, clears a pending sighting); a remote `DELETE` uses `forget`.
- **D31 resync.** A cursor answered with 410, a code containing `RESYNC`, or 400 drops the cursor and downloads everything: missing words
  are added; words in the snapshot that are gone from the server (tombstone expired after 180 days) are removed locally; never-synced local words stay
  and are pushed. The first sync is the same full download. (The contract does not define the resync-required response yet - B6.3 - so the detection is deliberately broad.)
- **Consent.** The settings switch calls `PUT /me/consents/DICTIONARY_SYNC` (`consentVersion` "1", `details.surface` = `settings`) and sets a local flag;
  the dictionary step runs only while it is on. 403 `CONSENT_REQUIRED` from the server (withdrawn elsewhere) turns the flag off. Guests: skipped locally
  and `GUEST_NOT_ALLOWED` handled (preferences still sync). 403 for another reason (entitlement) = `NOT_ALLOWED`, skipped. Switching off keeps the cursor and
  snapshot (so switching on again does not resurrect words deleted elsewhere meanwhile). `refresh()` adopts the server's latest decision on a new device.

## Tests (`./gradlew :sync:testDebugUnitTest`, JVM, MockWebServer over the real `:api` client against `FakeBackend`)
`SettingsSyncTest`: offline edit on device A while device B changed the server document -> PUT 409, GET, merged PUT; final server document holds both sides per
the rule and foreign members survive; bounded retries; typing languages converge (order, per-field merge, profile codes, deferral, retired profile kept).
`DestinationSyncTest`: profiles create/edit/delete converge (last writer wins, delete of an unsynced profile sends nothing, edit of a profile deleted elsewhere
re-creates it); rules hashed on device (independent HMAC check, no plaintext package name or key in any request, key fetched once), 409 retry merge, rule pointing at a profile.
`DictionarySyncTest`: words added on two devices converge (D18 explicit adds), delete propagates as a tombstone, lost response retried with the same ids and no duplicate,
local add is the later write, guests and missing consent handled, resync-required full download, invalid words not sent, account switch forgets state.

## Needs a device
WorkManager scheduling and constraints; the Keystore key (`qal.sync.v1`); the settings switch on screen; behaviour against the real backend (the fake is a stand-in:
`DictionarySyncIT` and the revision ITs cover the server side); the resync-required response once B6.3 defines it.

## Deferred
The destination-profile and app-rule **editing UI** (a list plus an edit dialog). The data layer, store and sync are complete and tested, but nothing consumes the
profiles yet (the destination chip and the per-app lookup in the IME belong to the AI/translation steps), so a UI now would be dead weight.
