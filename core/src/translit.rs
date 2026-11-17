//! Data-driven Latin to Geez transliteration (plan step C1.4; Qubee follows in C1.5).
//!
//! - [`Profile`]: a validated `TRANSLITERATION_RULES` artifact (`qal-transliteration-profile`,
//!   `language-data/profiles/`). All mappings are data; syllable forms come from the generated
//!   Fidel table (`composer`), never from hard-coded glyphs or code point arithmetic.
//! - [`PhoneticSession`]: the phonetic composing buffer. It keeps the raw Latin keystrokes,
//!   re-tokenises them by longest match after every key and shows the result as composing text.
//!   Backspace removes the last Latin keystroke and recomputes.
//!
//! Profiles are DRAFT until a native-speaker panel approves them (gate G0, rule N9); hosts read
//! the status through `KeyboardEngine::translit_profile`.

mod profile;
mod session;

pub use profile::{Profile, ProfileError, ProfileStatus, MAX_PROFILE_BYTES};
pub use session::PhoneticSession;
