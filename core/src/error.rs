//! Error type shared across the FFI boundary. Messages must never contain typed content or pack
//! content (N2).

use crate::pack::PackRejectReason;

/// Errors returned by fallible engine calls.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// A language pack failed verification or was refused by engine policy. `reason` is
    /// machine-readable; `detail` is a fixed human-readable explanation without pack content.
    #[error("pack rejected ({}): {detail}", .reason.code())]
    PackRejected {
        reason: PackRejectReason,
        detail: String,
    },
    /// The language code is not supported by this build.
    #[error("unsupported language: {code}")]
    UnsupportedLanguage { code: String },
    /// The call is not valid in the current engine state or mode.
    #[error("invalid state: {reason}")]
    InvalidState { reason: String },
    /// `EngineConfig` is invalid (e.g. a trusted key that is not a 32-byte Ed25519 key).
    #[error("invalid config: {reason}")]
    InvalidConfig { reason: String },
}
