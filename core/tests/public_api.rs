use qal_keyboard_core::{CoreError, EngineConfig, InputMode, KeyEvent, KeyboardEngine};

#[test]
fn typing_session_through_public_api() {
    let e = KeyboardEngine::new(EngineConfig { max_candidates: 5 });
    assert!(!e.version().is_empty());
    e.set_input_mode("en".into(), InputMode::Abc).unwrap();
    let committed: String = "hi"
        .chars()
        .map(|c| {
            e.on_key(KeyEvent::Char {
                text: c.to_string(),
            })
            .commit
        })
        .collect();
    assert_eq!(committed, "hi");
    assert_eq!(e.on_key(KeyEvent::Backspace).delete_before, 1);
    assert_eq!(e.reset().composing, "");
    assert!(matches!(
        e.load_pack(vec![1, 2, 3]),
        Err(CoreError::PackRejected { .. })
    ));
}
