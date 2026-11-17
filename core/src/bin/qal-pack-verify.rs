//! Verifies `.pack` files exactly as an app would: pins one public key, loads each pack into a
//! fresh engine (signature, manifest, size, SHA-256, layout validation) and prints its info.
//! Used by the L1.1 pack build checks. Exit code 0 only if every pack loads.
//!
//! Usage: qal-pack-verify <key_id> <public_key_hex> <file.pack>...
use qal_keyboard_core::{CoreError, EngineConfig, KeyStatus, KeyboardEngine, TrustedKey};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!("usage: qal-pack-verify <key_id> <public_key_hex> <file.pack>...");
        std::process::exit(2);
    }
    let Some(public_key) = decode_hex(&args[1]) else {
        eprintln!("public key is not valid hex");
        std::process::exit(2);
    };
    let mut failed = false;
    for path in &args[2..] {
        // Fresh engine per pack so rollback protection never interferes.
        let result = KeyboardEngine::new(EngineConfig {
            max_candidates: 5,
            trusted_keys: vec![TrustedKey {
                key_id: args[0].clone(),
                public_key: public_key.clone(),
                status: KeyStatus::Active,
            }],
            pack_limits: None,
        })
        .and_then(|engine| {
            let bytes = std::fs::read(path).map_err(|_| CoreError::InvalidConfig {
                reason: "cannot read file".to_string(),
            })?;
            engine.load_pack(bytes)
        });
        match result {
            Ok(i) => println!(
                "OK {path} {} {:?} v{} {:?} sha256={} size={} key={}",
                i.language_code,
                i.resource_type,
                i.version,
                i.release_channel,
                i.sha256,
                i.size_bytes,
                i.key_id
            ),
            Err(e) => {
                println!("FAIL {path} {e}");
                failed = true;
            }
        }
    }
    std::process::exit(i32::from(failed));
}

fn decode_hex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) || !s.is_ascii() {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}
