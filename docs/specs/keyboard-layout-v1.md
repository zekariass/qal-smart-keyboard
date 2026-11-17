# Keyboard layout resource, format v1 (`KEYBOARD_LAYOUT`)

Status: v1, implemented by plan step C1.3 (`core/src/composer/layout.rs`). The two source layouts
(`language-data/layouts/am-fidel-draft.json`, `ti-fidel-draft.json`) are **DRAFT** until a
native-speaker panel approves them (gate G0).

## 1. Where it lives

The artifact of a pack whose manifest says `resourceType: KEYBOARD_LAYOUT`
(`docs/specs/pack-format-v1.md`). The core parses and validates it **after** signature and
checksum verification; a layout that fails validation is rejected with `INVALID_ARTIFACT` and
nothing is activated. The layout `language` must equal the manifest `languageCode`.

The artifact is one UTF-8 JSON object (NFC), at most **64 KiB**. Unknown members are rejected, so
new features need a new `formatVersion`.

## 2. Syllable table

Layouts never contain syllables. They reference **consonant series** by id; the core resolves
syllables from a table generated from Unicode character names by
`language-data/scripts/gen_fidel_table.py` (`language-data/generated/fidel-table-v1.json`; the
same data is compiled into the core). Names, not code point arithmetic, are the source of truth
because the Ethiopic blocks have irregular gaps.

- Series id: the consonant part of the Unicode name, e.g. `S` (`ETHIOPIC SYLLABLE SA` = ሰ), `SH`,
  `QH` (ቐ), `KX` (ኸ), `GLOTTAL` (አ), `PHARYNGEAL` (ዐ). Labiovelar series carry a `W`: `QW`, `KW`,
  `XW`, `GW`, `QHW`, `KXW`, `GGW`. Sebatbeit and Gurage extension forms keep their prefix
  (`SEBATBEIT_M`, `GURAGE_QW`).
- Order ids and numbers: `A`=1, `U`=2, `I`=3, `AA`=4, `EE`=5, `E`=6 (sixth order), `O`=7,
  `WA`=8 (labialised "wa", e.g. ሏ), `OA`=9 (rare variants in the extended blocks).
- Labiovelar series have only the orders `A`, `I`, `AA`, `EE`, `E` (ቈ ቊ ቋ ቌ ቍ).

## 3. Members

```json
{
  "format": "qal-keyboard-layout",
  "formatVersion": 1,
  "language": "am",
  "layoutId": "am-fidel",
  "layoutVersion": "0.1.0",
  "status": "DRAFT",
  "interaction": ["LONG_PRESS", "TWO_KEY"],
  "defaultOrder": 6,
  "popupOrders": [1, 2, 3, 4, 5, 6, 7, 8],
  "vowelKeys": [{"order": 1, "label": "ሀ"}, {"order": 4, "label": "ሃ"}, {"order": 8, "label": "ዋ"}],
  "rows": [
    [{"series": "H", "label": "ሀ"}, {"series": "L", "label": "ለ"}],
    [{"series": "G", "label": "ገ", "labialized": "GW"}]
  ],
  "punctuation": ["።", "፣", "፤", "፥", "፦", "፧", "፨", "፡"],
  "digits": [{"digit": "1", "alternatives": ["፩", "፲"]}, {"digit": "0", "alternatives": ["፻", "፼"]}]
}
```

| Member | Req. | Meaning and rules |
|---|---|---|
| `format` | yes | `"qal-keyboard-layout"`. |
| `formatVersion` | yes | `1`. |
| `language` | yes | 2 to 3 lowercase letters; equals the manifest `languageCode`. The core uses layouts for `am` and `ti`. |
| `layoutId` | yes | `[a-z0-9-]{1,64}`. |
| `layoutVersion` | yes | SemVer. |
| `status` | yes | `DRAFT` or `APPROVED`. Hosts may badge or hide DRAFT layouts in production. |
| `interaction` | yes | Non-empty set of `LONG_PRESS` and/or `TWO_KEY`, no duplicates. |
| `defaultOrder` | no | Order shown when a TWO_KEY composition starts, 1 to 9, default **6** (SD 7.2: the sixth order, `s` gives ስ). If a series lacks it, its first form is used. |
| `popupOrders` | LONG_PRESS | Unique orders 1 to 9, in popup display order. Orders a series lacks are skipped for that key. |
| `vowelKeys` | TWO_KEY | Vowel-order keys, unique `order` 1 to 9, `label` shown on the key. Order **8** means "labialised": the series' own `WA` form if it has one (ሏ), else the `AA` form of the key's `labialized` series (ጓ, ቋ, ኳ, ኋ). |
| `rows` | yes | 1 to 8 rows, each 1 to 16 keys, at most 128 keys in total, no empty rows. |
| `rows[][].series` | yes | Known series id. A series may appear on one key only. |
| `rows[][].label` | yes | Text on the key: 1 to 4 characters, NFC, no control characters. |
| `rows[][].labialized` | no | Known series id (not the key's own). Its forms are appended to the key's long-press popup and feed order 8. |
| `punctuation` | no | At most 16 unique single characters from U+1360..U+1368 (፠ ። ፣ ፤ ፥ ፦ ፧ ፨ and ፡). |
| `digits` | no | Per ASCII digit key (unique), 1 to 8 unique Ethiopic numerals U+1369..U+137C offered as long-press alternatives. The drafts map 1 to 9 to `፩..፱` and `፲..፺`, and 0 to `፻` and `፼`. |

Validation also rejects: wrong `format`/`formatVersion`, unknown or missing members, unknown
series, duplicate keys, empty rows, non-NFC or control text, oversize artifacts, `LONG_PRESS`
without `popupOrders`, and `TWO_KEY` without `vowelKeys`. Rejection details are fixed strings and
never echo layout content (N2).

## 4. Host contract

`KeyboardEngine::fidel_layout(language)` returns the resolved view (`FidelLayout`): for each key
the `series_id`, `label`, `tap_text` (the default-order form) and the popup `forms`
(`FidelForm { order, label, text }`). Popup forms of a key's `labialized` series have
`order = n + 10` (11..19). Interaction at run time, in `DirectFidel` mode:

- LONG_PRESS: popup choice, punctuation and numerals are sent as `KeyEvent::Char { text }`.
  In a LONG_PRESS-only layout a tap sends `Char { tap_text }`.
- TWO_KEY: consonant key sends `FidelConsonant { series_id }` (engine shows the default order as
  composing text), vowel key sends `FidelVowel { order }` (engine commits that order).
- Backspace after a commit returns `delete_before: 1` (one grapheme cluster).

## 5. Draft layouts

Both layouts: `interaction = [LONG_PRESS, TWO_KEY]`, `defaultOrder = 6`, popup orders 1 to 8,
vowel keys 1 to 7 plus 8 (ዋ), 34 or 35 keys in rows of seven, full Ethiopic punctuation, Ethiopic
numerals on all ten digits.

- **Amharic** (34 series): ሀ ለ ሐ መ ሠ ረ ሰ ሸ ቀ በ ቨ ተ ቸ ኀ ነ ኘ አ ከ ኸ ወ ዐ ዘ ዠ የ ደ ጀ ገ ጠ ጨ ጰ ጸ ፀ ፈ ፐ.
  Homophone families (ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ) are all present so users can spell conventionally.
  No ቐ series. Labialised links: ቀ→QW, ኀ→XW, ከ→KW, ኸ→KXW, ገ→GW.
- **Tigrinya** (35 series): the Amharic set plus **ቐ (`QH`, links `QHW`)** after ቀ. ኸ (`KX`, links
  `KXW`) is shared with Amharic.

Open points for the native-speaker review: whether Amharic should carry ኸ and ቨ on the main keys
or behind a long-press; whether Tigrinya should hide rarely used homophones; the key order; and
whether the TWO_KEY default order should be the first order (ሀ-style) for some users.
