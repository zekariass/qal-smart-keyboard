# UI localisation glossary: Amharic, Tigrinya, Afaan Oromoo (DRAFT)

**Status: DRAFT (rule N9).** Every `values-am`, `values-ti` and `values-om` string of the Android app and keyboard
(`android/app/src/main/res/values-*/strings.xml`, `android/ime/src/main/res/values-*/strings.xml`) is a machine-assisted
first draft. It ships behind the system language / the in-app "App language" setting (ADR 0032) and must be reviewed by
the native-speaker panel before it is called final. This file is the panel's starting point: the glossary the drafts
follow, the style rules, and the strings the drafter was least sure about.

How to review: change the glossary first (a term changed here is changed in every string that uses it), then the
strings. Keep the format arguments (`%1$s`, `%2$d`, `%%`) and the escaped apostrophes (`\'`) exactly; the unit test
`StringResourcesTest` fails the build otherwise.

## Style rules

| Rule | Amharic (am) | Tigrinya (ti) | Afaan Oromoo (om) |
|---|---|---|---|
| Script | Ge'ez (Fidel) | Ge'ez (Fidel), ኣ for initial *a* (ኣብ, ኣፕ) | Qubee (Latin), hudhaa `'` escaped as `\'` |
| Addressing the user | Polite (honorific) forms: ይቀጥሉ, ይምረጡ, የእርስዎ. Gender-neutral. | 2nd person plural as polite form: ቀጽሉ, ምረጹ, ናትኩም. Gender-neutral. | 2nd person plural as polite form: itti fufaa, filadhaa, keessan. |
| Buttons and chips | Short polite imperative (ይቅዱ, ያስገቡ) or a noun (ትርጉም) when the polite verb is too long for a keyboard chip | Plural imperative (ቅድሑ, ኣእትዉ) or a noun (ትርጉም) | Plural imperative (garagalchaa, galchaa) or a noun (hiikkaa); sign in / out use the usual verbal nouns *Seenuu* / *Ba'uu* |
| Sentence end | ። | ። | . |
| Comma / list | ፣ | ፣ | , |
| Question mark | ? | ? | ? |
| Quotation marks | « » | « » | “ ” |
| Digits | Western (1, 2, 3) as in the source; Ethiopic numerals only where the source means them | same | Western |
| Brand and product names | Qal, Qal Smart Keyboard, Google, Ethio telecom, Safaricom, Android, JSON, SMS, QWERTY, ABC, AI, Premium: unchanged, Latin | same | same |
| Language self-names | አማርኛ, ትግርኛ, Afaan Oromoo, English (the App language picker shows each in its own language, not translated) | same | same |

Tigrinya: the drafts aim at vocabulary understood in both Ethiopia (Tigray) and Eritrea. Where usage differs, the
more international or technical term was chosen (ኢንተርነት, ሰርቨር, ኢመይል) and the choice is listed below.

## Glossary

| English | Amharic | Tigrinya | Afaan Oromoo | Note |
|---|---|---|---|---|
| keyboard | የቁልፍ ሰሌዳ | ኪቦርድ | kiiboordii | ti: ኪቦርድ is the everyday word; ሰሌዳ መጽሓፊ is a possible purist alternative |
| Qal Smart Keyboard | Qal Smart Keyboard | Qal Smart Keyboard | Qal Smart Keyboard | product name, never translated |
| app | መተግበሪያ | ኣፕ | appii | |
| settings | ቅንብሮች | ቅጥዕታት | qindaa'ina | |
| setup (first-run flow) | ማዋቀር | ምድላው | qophii | never the same word as "settings" (the settings screen shows both) |
| App language | የመተግበሪያ ቋንቋ | ቋንቋ ኣፕ | afaan appii | |
| System default | የስልኩ ነባሪ | ናይ ስልኪ ዝተዳለወ | kan sirnaa | |
| language | ቋንቋ | ቋንቋ | afaan | |
| type, typing | መተየብ (ይተይቡ) | ምጽሓፍ (ጽሓፉ) | barreessuu (barreessaa) | |
| writing mode | የአጻጻፍ ዘዴ | ኣገባብ ጽሕፈት | akkaataa barreessuu | |
| Phonetic (mode) | ፎነቲክ | ፎነቲክ | Foonetikii | short label in the bar |
| Fidel, Fidel grid | ፊደል, የፊደል ሰሌዳ | ፊደል, ሰሌዳ ፊደል | Fidala, gabatee Fidalaa | the selector label ፊደል is not translated |
| translate / translation | ተርጉም → ይተርጉሙ / ትርጉም | ተርጉሙ / ትርጉም | hiikaa / hiikkaa | chip uses the noun |
| theme | ገጽታ | ገጽታ | bifa | |
| sign in / sign out | ይግቡ / ይውጡ | እተዉ / ውጹ | Seenuu / Ba'uu | |
| account | መለያ | ኣካውንት | akkaawuntii | |
| guest session | የእንግዳ ክፍለ ጊዜ | ናይ ጋሻ ኣጠቓቕማ | tajaajila keessummaa | om avoids *seenaa* (history) |
| privacy | ግላዊነት | ብሕታውነት | dhuunfaa | |
| privacy notice | የግላዊነት ማስታወቂያ | መግለጺ ብሕታውነት | beeksisa dhuunfaa | |
| consent | ፈቃድ | ፍቓድ | eeyyama | also used for "permission" |
| permission (microphone) | ፈቃድ | ፍቓድ | hayyama | |
| crash reports | የብልሽት ሪፖርቶች | ጸብጻባት ብልሽት | gabaasa kufaatii | |
| voice typing | በድምፅ መተየብ | ብድምጺ ምጽሓፍ | sagaleedhaan barreessuu | |
| speech service | የንግግር አገልግሎት | ኣገልግሎት ዘረባ | tajaajila dubbii | |
| microphone | ማይክሮፎን | ማይክሮፎን | maayikiroofoonii | |
| emoji | ኢሞጂ | ኢሞጂ | emojii | |
| skin tone | የቆዳ ቀለም | ሕብሪ ቆርበት | halluu gogaa | |
| Word Art | የቃል ጥበብ | ስነ-ጥበብ ቃላት | Aartii Jechaa | feature name, translated |
| style (Word Art) | ስልት | ቅዲ | akkaataa | |
| greeting | ሰላምታ | ሰላምታ | nagaa gaafachuu | |
| e-mail | ኢሜይል | ኢመይል | imeelii | |
| subject | ርዕስ | ኣርእስቲ | mata-duree | |
| recipient / sender | ተቀባይ / ላኪ | ተቐባሊ / ሰዳዲ | fudhataa / ergaa | |
| draft (your text) | ረቂቅ | ንድፊ | wixinee | |
| DRAFT (N9 tag) | ረቂቅ | ንድፊ | WIXINEE | the tag on suggestions and keys |
| regenerate | እንደገና ይፍጠሩ | ደጊምኩም ፍጠሩ | irra deebi'aa uumaa | |
| copy | ይቅዱ | ቅድሑ | garagalchaa | |
| copied | ተቀድቷል | ተቐዲሑ | garagalfameera | |
| insert | ያስገቡ | ኣእትዉ | galchaa | |
| replace | ይተኩ | ተክኡ | bakka buusaa | |
| paste | ይለጥፉ | ለጥፉ | maxxansaa | |
| clipboard | ቅንጥብ ሰሌዳ | ሰሌዳ ቅዳሕ | kuusaa garagalchaa | |
| delete | ይሰርዙ | ደምስሱ | haqaa | |
| cancel | ይቅር | ሰርዙ | dhiisaa | ti: ሰርዙ = cancel, ደምስሱ = delete |
| back | ይመለሱ | ተመለሱ | deebi'aa | |
| continue | ይቀጥሉ | ቀጽሉ | itti fufaa | |
| done | ተጠናቋል | ተወዲኡ | xumurameera | Enter key: ጨርስ / ወድእ / Xumuri |
| retry, try again | እንደገና ይሞክሩ | ደጊምኩም ፈትኑ | irra deebi'aa yaalaa | |
| close | ይዝጉ | ዕጸዉ | cufaa | |
| edit | ያርትዑ | ኣርምዉ | gulaalaa | |
| share | ያጋሩ | ኣካፍሉ | qooddadhaa | |
| search | ይፈልጉ | ድለዩ | barbaadaa | Enter key uses the short form |
| premium, Premium themes | Premium, የPremium ገጽታዎች | Premium, ገጽታታት Premium | Premium, bifoota Premium | plan name kept |
| plan | ጥቅል | ጥቕሊ | paakeejii | as in Ethio telecom's apps |
| upgrade | ያሻሽሉ | ኣመሓይሹ | fooyyessaa | |
| update (the app) | ያዘምኑ | ኣሐድሱ | haaromsaa | |
| offline | ከመስመር ውጭ | ካብ መስመር ወጻኢ | toora ala | |
| internet, connection | ኢንተርኔት, ግንኙነት | ኢንተርነት, ምትእስሳር | interneetii, walqunnamtii | |
| server | አገልጋይ | ሰርቨር | sarvarii | |
| device, phone | መሣሪያ, ስልክ | መሳርሒ, ስልኪ | meeshaa, bilbila | "this phone" preferred over "this device" in short text |
| personal dictionary | የግል መዝገበ ቃላት | ናይ ውልቀ መዝገበ ቃላት | galmee jechootaa dhuunfaa | |
| suggestions (candidate strip) | ጥቆማዎች | እማመታት | yaadota | |
| AI, AI Assist | AI, የAI እገዛ | AI, ሓገዝ AI | AI, Gargaarsa AI | "AI" kept |
| grammar | ሰዋስው | ሰዋሰው | seerluga | |
| rewrite | እንደገና ይጻፉ | ደጊምኩም ጽሓፉ | irra deebi'aa barreessaa | |
| explain | ያብራሩ | ኣብርሁ | ibsaa | |
| summarise | ያጠቃልሉ | ጠቕልሉ | cuunfaa | |
| reply | መልስ / ይመልሱ | መልሲ / መልሱ | deebii / deebisaa | |
| tone | ቃና | ቃና | akkaataa | |
| professional / formal / friendly / polite | ሙያዊ / መደበኛ / ወዳጃዊ / ትሁት | ሞያዊ / ወግዓዊ / ዕርካዊ / ትሑት | ogummaa / seera qabeessa / michummaa / kabajaa | |
| history | ታሪክ | ታሪኽ | seenaa | |
| favourite | ተወዳጅ | ፍቱው | jaallatamaa | |
| export (data) | ወደ ውጭ መላክ | ምውጻእ | baasuu | |
| numerals, Ethiopic | ቁጥሮች, የግዕዝ | ቁጽርታት, ግእዝ | lakkoofsota, Gi'iizii | |
| globe key | የሉል ቁልፍ | መጠወቒ ሉል | furtuu addunyaa | |
| space bar | የክፍተት ቁልፍ | መጠወቒ ባዶ ቦታ | furtuu iddoo duwwaa | |
| tap / long-press | ይንኩ / ተጭነው ይያዙ | ጠውቑ / ጸቒጥኩም ሓዙ | tuqaa / tuqaatii qabadhaa | |
| Shift, Enter, ABC, ?123 | unchanged (key names) | unchanged | unchanged | |
| native speakers | የቋንቋው ተወላጅ ተናጋሪዎች | ተዛረብቲ ቋንቋ ኣደ | dubbattoota afaan dhalootaa | |

## Strings the drafter was least sure about

Filled per language below. "Why" is the doubt; the panel decides.

### Amharic (am)

| key | draft | why |
|---|---|---|
| ime: kb_mode_phonetic / onb_mode_phonetic | ፎነቲክ / ፎነቲክ QWERTY (የሚመከር) | Loan word; users may know "phonetic" only in English. A native term (e.g. በድምፅ አጻጻፍ) would be too long for the bar. |
| ime: kb_enter_* | ሂድ, ፈልግ, ላክ, ቀጣይ, ቀዳሚ, ጨርስ | Must fit on the Enter key; masculine short imperatives chosen over polite forms (ይሂዱ, ይፈልጉ) for width. Panel to confirm the exception. |
| ime: kb_key_ethiopic_full_stop / kb_key_ethiopic_comma | አራት ነጥብ / ነጠላ ሰረዝ | Traditional names of ። and ፣; check TalkBack users recognise them. |
| ime: kb_key_apostrophe | አፖስትሮፍ፣ ሁዳ | "hudhaa" (Oromo) transcribed as ሁዳ; may be unfamiliar to Amharic speakers. |
| ime: kb_key_shift_locked | Shift፣ ዘላቂ አቢይ ሆሄ በርቷል | "caps lock" has no common Amharic term (Ge'ez has no case). |
| ime: kb_key_vowel_order (+active/unavailable) | የአናባቢ ደረጃ %1$d | Fidel "orders" (ግዕዝ, ካዕብ, ...) are usually called ቤት/ደረጃ; ደረጃ chosen, ቤት may be more traditional. |
| ime: wordart_tab / settings_wordart_* | የቃል ጥበብ | Feature name translated; "Word Art" might be better kept as a brand-like name. |
| ime: ai_assist_chip | እገዛ | Very short noun for "Assist"; ረዳት (assistant) is an alternative. |
| ime: ai_rewrite (chip) | እንደገና ጻፍ | Masculine imperative kept for chip width; polite ይጻፉ used in buttons (ai_run_rewrite). |
| ime: theme_name_* (e.g. ናና አመዳይ, የእንጆሪ ምሽት, ዘመናዊ ቅልመት) | descriptive translations | Creative names; Mint Frost and Berry Dusk are loose. ክላሲክ/ኤመራልድ/ግራፋይት are loans. |
| ime: wordart_style_fraktur, sans*, monospace, double_struck | ፍራክቱር, ሳንስ-ሰሪፍ, እኩል ስፋት, ድርብ መስመር | Typographic terms with no established Amharic equivalents. |
| app: settings_translit_profile | የፊደል ልውውጥ መገለጫ፦ አዲሱ ንቁ (ራስ-ሰር) | "Transliteration profile" is technical; wording is awkward. |
| app: settings_haptics | የንዝረት ግብረ መልስ | Literal "vibration feedback"; check against Android's own Amharic settings wording. |
| app: si_age_confirm | በምኖርበት አገር መለያ ለመክፈት የሚያስችል ዕድሜ ላይ መሆኔን አረጋግጣለሁ። | Legal statement; first person kept as in source. |
| app: consent_payment_* | የክፍያ ፈቃድ | "Payment mandate" (recurring authorisation) has no fixed Amharic term. |

Deviations from the glossary:
- language_name_om = ኦሮምኛ (the common Amharic name) in the keyboard/AI language lists; app strings that name the language in prose (onb_welcome_intro, wordart_app_*) use አፋን ኦሮሞ, matching the source's use of the self-name. Panel may want one form everywhere.
- Short chip/key labels (kb_enter_*, ai_rewrite, emoji_delete, kb_key_delete, ai_source_clear, pd_show/hide, pv_delete_item, pv_retention_save, pv_load_more) use the short masculine imperative or a noun instead of the polite form, for width; dialog/screen buttons use the polite form.
- ai_cancel/pd_cancel = ይቅር (glossary); ai_retry chip = እንደገና (short), voice_retry = እንደገና ይሞክሩ.
- crash reports switch, export/delete buttons in first person (መረጃዬን አውጣ, መለያዬን ሰርዝ) to mirror the source's "my".

### Tigrinya (ti)

| key | draft | why |
|---|---|---|
| ime_name / keyboard (everywhere) | ኪቦርድ | Loanword chosen for both Ethiopia and Eritrea; a purist alternative (ሰሌዳ መጽሓፊ) may be preferred by the panel. |
| kb_mode_phonetic, onb_mode_phonetic | ፎነቲክ, ፎነቲክ QWERTY (ዝምከር) | Loanword; no established Tigrinya term for Latin-to-Ge'ez phonetic typing. |
| kb_enter_go / kb_enter_previous / kb_enter_done | ኪድ / ዝሓለፈ / ወድእ | Enter-key labels use the short singular imperative or adjective because the plural polite form does not fit the key; check naturalness. |
| ai_tone_apologetic, ai_email_tone_apologetic | ይቕሬታ | Noun ("apology") used as a tone chip; an adjective form may read better. |
| ai_email_tone_persuasive | ኣእማኒ | "Convincing"; check it is not read as "trustworthy". |
| ai_email_tone_casual | ልሙድ | "Ordinary/usual"; an informal-register word may fit better. |
| kb_key_ethiopic_full_stop / kb_key_ethiopic_comma / kb_key_comma | ኣርባዕተ ነጥቢ ግእዝ / ነጠላ ሰረዝ ግእዝ / ኮማ | Traditional punctuation names (ኣርባዕተ ነጥቢ is ።); ኮማ for ASCII comma is a loanword. |
| kb_key_vowel_order (+ active/unavailable) | መደብ ኣናባቢ %1$d | "Vowel order" of the Fidel grid; teachers may say ሳድስ/ራብዕ etc. or ቅርጺ; check term. |
| kb_key_apostrophe | ኣፖስትሮፊ፣ ሁዳ | Oromo hudhaa spelled ሁዳ in Fidel; check spelling. |
| settings_haptics | ምንቅጥቃጥ ምልሻ | "Vibration response" for haptic feedback; may be unclear. |
| settings_translit_profile | ፕሮፋይል ትራንስሊተሬሽን: እቲ ሓድሽ ንጡፍ (ባዕሉ) | Technical loanwords. |
| wordart_tab / settings_wordart_title | ስነ-ጥበብ ቃላት | Feature name per glossary; check it reads as decorative text, not "literature". |
| theme names (theme_name_*) | e.g. ናዕናዕ በረድ (Mint Frost), ምሸት ፍረ (Berry Dusk), ዘመናዊ ሕብሪ ደረጃ (Modern Gradient) | Free poetic renderings; ናዕናዕ (mint) and ሕብሪ ደረጃ (gradient) especially unsure. |
| wordart_style_* letter styles | ሰያፍ (italic), ጽሕፈት ኢድ (script), ማዕረ ስፍሓት (monospace), ፍራክቱር, ሳንስ-ሰሪፍ | Typography terms borrowed from Amharic usage (ሰያፍ) or transliterated. |
| emoji_tone_1..5 | ጻዕዳ / ማእከላይ-ጻዕዳ / ማእከላይ / ማእከላይ-ጸሊም / ጸሊም ሕብሪ ቆርበት | "White/black" for skin tones may be read as too literal; ብሩህ/ጸዊግ could be softer. |

Ethiopia / Eritrea choices:
- ኢንተርነት, ሰርቨር, ኢመይል, ኣፕ, ኪቦርድ, ፕሮፋይል: international loanwords used in both countries.
- ኣካውንት (not ሕሳብ) for account; ጥቕሊ for plan (follows the glossary; Eritrean users may expect ፓኬጅ).
- ቁጽሪ (number), ስልኪ (phone): shared. ፍቱው (favourite), ጋሻ (guest): shared.
- language_name_om = ኦሮምኛ (the usual Tigrinya name); the Oromo self-name Afaan Oromoo appears in brackets in lang_om.
- language_name_am = ኣምሓርኛ (Tigrinya spelling with ኣ); lang_am adds the Amharic self-name (አማርኛ).

Deviations from the glossary:
- Delete key / emoji delete (kb_key_delete, emoji_delete) use the noun ምድምሳስ (key name, not a command); buttons use ደምስሱ.
- ai_rewrite toolbar chip uses the noun ዳግመ ጽሕፈት (short, neutral); the panel action/run buttons use ደጊምኩም ጽሓፉ.
- User-voice labels ("my ...") keep first person singular: ሓበሬታይ ኣውጽእ, ኣካውንተይ ደምስስ, መዝገበ ቃላተይ ኣሰማምዕ, ታሪኽ AI ዓቅብ.
- Enter-key labels use short singular forms (ኪድ, ድለ, ስደድ, ወድእ) for space.
- System default: ናይ ስልኪ ዝተዳለወ (as in glossary). themes_system (the "System" theme) is ስርዓት.

### Afaan Oromoo (om)

| key | draft | why |
|---|---|---|
| kb_enter_previous | Duraa | "Previous" on the Enter key; *Kan duraa* is clearer but too long for the key |
| kb_enter_go | Deemi | Singular imperative chosen for the tiny Enter key (the rest of the UI uses polite plural) |
| ai_translate / ai_action_translate | Hiikkaa | Noun used as chip label; some UIs use the imperative *Hiiki* |
| settings_numerals_ethiopic | Gi'iiziin ni dhiyaata | Spelling of Ge'ez in Qubee (Gi'iizii vs Geez) not standardised |
| kb_key_apostrophe | Hudhaa | Source says "Apostrophe, hudhaa"; in Oromo the key is simply the hudhaa |
| pv_hours / pv_minutes / pv_seconds | %1$d sa'a / %1$d daq / %1$d sek | Unit abbreviations are not standardised in Oromo UI |
| theme_name_* (e.g. berry_dusk, mint_frost, modern_gradient) | Galgala Ija Mukaa, Qorra Mintii, Halluu Ammayyaa | Poetic theme names translated loosely; may be better kept in English |
| wordart_style_* letter styles (fraktur, double_struck, monospace, small_caps) | Fraktur, Sarara dachaa, Bal'ina walqixaa, Qubee guddaa xixiqqaa | Typography terms have no settled Oromo equivalents |
| wordart_style_hol_genna | Ganna, Qillee | Christmas is *Qillee*/*Ganna* regionally; source pairs Genna with Christmas |
| consent_payment_title | Hayyama kaffaltii | "Payment mandate" (recurring debit authorisation) has no common Oromo term |
| emoji_category_smileys | Seequmsaa fi miira | "Smileys" rendered as smiles; Google's Oromo emoji labels not verified |
| ai_tone_honorific | Kabaja olaanaa | Honorific register is less grammaticalised in Oromo than in Amharic |
| settings_translit_profile | Piroofaayilii jijjiira qubee… | "Transliteration profile" is technical; wording may not be understood |

Deviations from the glossary:
- Enter-key labels (kb_enter_*) use the singular imperative (Deemi, Barbaadi, Ergi, Xumuri) for brevity; everything else uses the polite plural.
- "Plan" uses *paakeejii* (as glossary), but the "See plans" link is *Paakeejiiwwan ilaalaa*.
- Language names use the long form *Afaan Amaaraa / Afaan Tigiraay / Afaan Ingiliffaa*; the short forms (*Amaaraa*, *Tigiraay*, *Ingiliffa*) only where the source has a short form (lang_*_short, kb_mode_english, subtype_english_qwerty).
- "Guest session" is *tajaajila keessummaa* (avoids *seenaa* = history), as the glossary note says.

### Shared decisions for the panel

- Short keyboard labels (Enter key, Delete key, a few chips) use a short imperative or a noun instead of the polite form, for width. Dialog and screen buttons use the polite form. Confirm the exception, or give shorter polite forms.
- Buttons that say "my" in English (Export my data, Delete my account) stay in the first person.
- Oromo's name in Amharic and Tigrinya lists is ኦሮምኛ; prose in the app uses the self-name (Afaan Oromoo / አፋን ኦሮሞ). Pick one form.
- Theme and Word Art style names are loose, descriptive translations. The panel may prefer to keep some in English.
