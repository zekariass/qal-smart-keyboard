# Keyboard bug fixes, UI improvements and feature completion — implementation report

**Date:** 2026-11-19 · **Benchmark:** Google Gboard · **Main at:** 047643b (+ this report) · **CI:** green on every push of this work

Statuses: **Fixed** · **Implemented** · **Tested on device** (owner's Samsung SM-A346B, Android, play debug build) · **Tested (automated)** · **Partially implemented** · **Blocked** · **Not implemented**.

## 1. Reported issues, root causes, fixes

| # | Issue | Root cause | Fix | Status |
|---|---|---|---|---|
| 1 | Translate box: no reliable tap, no cursor movement, no mid-text editing | In-keyboard fields were append-only buffers (ADR 0031 §4); nothing mapped a touch to a text offset | One `EditorBuffer` + `EditorView` (ADR 0038): tap-to-position (cluster-safe, bidi caret), selection handles, Cut/Copy/Paste/Select all (sensitive clips never pasted), composition mid-text, cursor kept across language changes/swap | Fixed · **Tested on device** (tap at start, typed በ before ሰላም → በሰላም) |
| 2 | AI sparkle icon | — | "AI" wordmark (bar + tiles), fixed size, active/lock states (ADR 0041) | Implemented · **Tested on device** |
| 3 | AI tools squeezed in a narrow row | — | AI tools grid in place of the keys: 15 tiles, every one a served endpoint; old row retired | Implemented · **Tested on device** (Fix grammar ran via the real server; plan locks + allowance line shown) |
| 4 | Ge'ez numerals by default | — | am/ti native layouts type ፩…፱ and ፲ (no zero in Ethiopic); Western on long-press; dial pads, English mode, om/en/ar unchanged (ADR 0043) | Implemented · **Tested on device** |
| 5 | Repeated characters (ግግር) | (a) core phonetic session collapsed every doubled consonant as gemination, so `ggr` → ግር; (b) multi-touch rollover dropped a waiting release-action key | Doubled consonant collapses only before a vowel; rollover commits the waiting key first; no delays added | Fixed · **Tested on device** (ggr → ግግር, selam → ሰላም) |
| 6 | Word Art results not clickable | ListView rows were focusable, so item clicks were dropped; app cards had no click | Tap commits the styled text at the cursor (keyboard) / copies with snackbar (app) | Fixed · **Tested on device** (✨ ሰላም ✨ inserted in Chrome) — note: tapping a *sample* before typing does nothing |
| 7 | Word Art "Tap to type" / Email "Type it" input; voice | Same append-only field; app screens lacked `imePadding` | Shared editor in all panels; app fields `adjustResize` + `imePadding`; dictation into fields (keyboard + app `DictationModel`) | Fixed · input **tested on device**; voice: flow and error path **tested on device**, Amharic recognition not available on this phone's speech service |
| 8 | Theme gallery + ~20 themes | — | 20 new free themes appended (existing 20 ids/tiers/order untouched, snapshot-tested), all 40 pass the contrast test; strip shows every theme; picker grouped Light/Dark/Gradient/Premium (ADR 0042) | Implemented · **Tested on device** (apply, "Selected", persists after relaunch) |
| 9 | Translation languages | Only the five typing languages, hard-coded in several places; only en↔x routes | 190 translation-only languages (Google Cloud Translation NMT list, autonyms, RTL flags) — typing languages unchanged; per-model language lists, no N×N rows (V24); `/ai/capabilities` catalogue with ETag; searchable lists (ADR 0040) | Implemented · Tested (automated); on device the catalogue is live, non-English pairs not yet exercised with real providers |
| 10 | Layout options + tools grid | — | Latin arrangements QWERTY/AZERTY/QWERTZ/Dvorak/Colemak per language; number-row switch; Fidel long-press grid; "Layout" tile + panel, applies instantly, keeps text (ADR 0043) | Implemented · **Tested on device** (AZERTY applied incl. phonetic hints, restored QWERTY) |
| 11 | AI "That took too long" | Phone APK built with the emulator-only default base URL `http://10.0.2.2:8080` → every connection hung to the call timeout; wireless-adb reconnect had also dropped `adb reverse` | Rebuilt with `-Pqal.apiBaseUrl=http://localhost:8080`, tunnel restored; client: 5 s connect timeout, "Can't reach the Qal server" distinct from "That took too long", no stuck loading, same-key retries; README documents phone builds | Fixed · **Tested on device** (ሰላም → "Hello.", በሰላም → "In peace.", Fix grammar result) |
| 12 | Gboard-style prediction | Audit: en had none; next-word, autocorrect, undo were in the core ranker but not wired; no learning switch | Core `predict.rs`: suggestions (en, om, am/ti Phonetic + Fidel completions, ar user words), autocorrect (en on, om off), Backspace undo, next-word incl. after sentence ends, learning switch, 3-slot strip (ADR 0039) | Implemented · **Tested on device** (next-word after Ge'ez words; en `hel` → hello / hel / he). English autocorrect practically dormant on the 100-word seed (see limitations) |
| — | Owner add-on: N T K P capitals, ቐ series, ፣ ። direct | Capitals were folded (ኝ ጥ ኽ ጵ only alternatives); ቐ only via `qh`; ። ፣ only via `..` `,,` | Profiles am 0.3.0 / ti 0.4.0 (DRAFT): N→ኝ T→ጥ K→ኽ P→ጵ default, ti Q→ቐ series; ። ፣ direct keys in am/ti Phonetic + Fidel; no auto-capitalisation in Phonetic | Implemented · **Tested on device** (ኛ ጠ ኽ ጳ, ቐቑቓ, ። key) |
| — | Found in QA: dark theme on light system → invisible status-bar icons | System bars followed system night mode, app follows the keyboard theme | Bars restyled with the app's choice | Fixed · tested on emulator |

## 2. Existing functionality preserved
All earlier features (settings redesign ADR 0036, Arabic ADR 0034, toolbar/tools/clipboard ADR 0035, translate panel ADR 0037, Afaan Oromoo/Tigrinya audit) remain; every merge was tested on the combined state before landing.

## 3. Coverage
- **Typing languages (unchanged set):** Amharic, Tigrinya (Phonetic + Fidel), Afaan Oromoo (Qubee), English, Arabic (opt-in).
- **Translation languages:** 190 (translation-only rows never appear as keyboard languages). Quality BETA; only en↔am/ti and ti→en exercised with the real provider.
- **AI actions tested on device:** Translate (ti→en ×2, Insert), Fix grammar. Locked on the account's Free plan (not testable without a plan grant): Reply, Write Email, Explain, Summarise, tones.

## 4. Automated tests and builds (final state)
- Rust core: 298 passed (+ fmt, clippy clean). Language-data: 41 + pack check.
- Android: core-bindings 1, data 53, sync 25, api 42, ai-client 92, ime 858, app 626 (direct + play) — 0 failures.
- Backend: 1163 unit + 419 integration (translation catalogue branch), JDK 25 compile green.
- APK: `android/app/build/outputs/apk/play/debug/app-play-arm64-v8a-debug.apk` (package `com.qalsmartkeyboard.app`, versionName 1.0, play debug, API base `http://localhost:8080`), installed 2026-11-19 22:20, user data kept, Qal selected as the active keyboard.

## 5. Screenshots
Device and render screenshots are in the session scratchpad (`phone-qa/`, `render/`, `renders/`), not committed: the device screenshots include another app's floating video window and must not be shared as-is.

## 6. Remaining issues and limitations
1. **English autocorrect** rarely fires: the shipped English seed has 100 words; a licensed frequency list (SCOWL/12dicts, Google Books Ngram CC BY 3.0, wordfreq CC BY-SA 4.0 — legal review) is needed (rule N10). Om and Arabic predictions also need real data.
2. **Voice:** this phone's speech service reports Amharic as unavailable; installing/updating Google's speech services is the likely fix. Dictation of real speech was not tested.
3. **Premium AI actions** untested on device (Free plan). A dev plan grant (`infra/scripts/dev-grant-plan.sh`) would allow it.
4. **Native-speaker review (G0):** all new am/ti/om strings, profile changes (capitals, Q, ti `a`→ኣ), Fidel layout variants.
5. **Numerals migration:** users who had deliberately chosen Western digits get Ethiopic once (indistinguishable from "never chose"); one tap in Preferences restores it.
6. **UX nits seen on device:** AI result row's last button ("Cancel") is cut at the edge (row scrolls); tapping a Word Art sample before typing does nothing (no hint); after a space the bar shows predictions instead of shortcuts (Gboard-like, tools stay in the grid).
7. Not covered: TalkBack, landscape, other phones/Android versions, a sensitive clip from a password manager.
