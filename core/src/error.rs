//! Error type shared across the FFI boundary. Messages must never contain typed content (N2).
//! Filled in progressively; pack errors arrive with C1.2.

/// Errors returned by fallible engine calls.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// A language pack failed verification or parsing.
    #[error("pack rejected: {reason}")]
    PackRejected { reason: String },
    /// The language code is not supported by this build.
    #[error("unsupported language: {code}")]
    UnsupportedLanguage { code: String },
    /// The call is not valid in the current engine state or mode.
    #[error("invalid state: {reason}")]
    InvalidState { reason: String },
}
