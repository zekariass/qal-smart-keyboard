//! Data-driven Latin to Geez transliteration (plan step C1.4; Qubee follows in C1.5).
//!
//! - [`Profile`]: a validated `TRANSLITERATION_RULES` artifact (`qal-transliteration-profile`,
//!   `language-data/profiles/`). All mappings are data; syllable forms come from the generated
//!   Fidel table (`composer`), never from hard-coded glyphs or code point arithmetic.
//! - [`PhoneticSession`]: the phonetic composing buffer. It keeps the raw Latin keystrokes,
//!   re-tokenises them by longest match after every key and shows the result as composing text.
//!   Backspace drops the last syllable's keystrokes and recomputes, so one press removes one
//!   rendered Fidel character (`ka` ካ goes at once, not via ክ).
//!
//! Profiles are DRAFT until a native-speaker panel approves them (gate G0, rule N9); hosts read
//! the status through `KeyboardEngine::translit_profile`.

mod profile;
mod session;

pub use profile::{Profile, ProfileError, ProfileStatus, MAX_PROFILE_BYTES};
pub use session::PhoneticSession;
