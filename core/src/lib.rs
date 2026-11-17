//! Qal Smart Keyboard core: the offline language engine shared by Android and iOS.
//!
//! # Privacy rules
//! The core must never log or persist typed content (plan rules N1 and N2). It performs no
//! network access, writes no files, and no error message or debug output may include user text.
//!
//! The public surface is exposed through UniFFI proc-macros and kept FFI-friendly: owned types,
//! no lifetimes, no generics, `Arc` objects.

#![forbid(unsafe_code)]

pub mod calendar;
pub mod composer;
mod engine;
pub mod error;
pub mod langid;
pub mod lexicon;
pub mod pack;
pub mod ranker;
pub mod segmenter;
pub mod translit;

pub use composer::{FidelDigit, FidelForm, FidelKey, FidelLayout, FidelVowelKey};
pub use engine::{
    Candidate, CandidateKind, EditorUpdate, EngineConfig, InputMode, KeyEvent, KeyboardEngine,
};
pub use error::CoreError;
pub use pack::{
    KeyStatus, PackInfo, PackLimits, PackPlatform, PackRejectReason, ReleaseChannel, ResourceType,
    TrustedKey,
};

uniffi::setup_scaffolding!();
