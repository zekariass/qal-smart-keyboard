package com.qalsmartkeyboard.languages.pack;

/** {@code resource_signing_keys.key_status} (pack spec section 7.2). */
public enum KeyStatus {
	/** Signs new packs. */
	ACTIVE,
	/** No new packs; packs it signed still verify. */
	RETIRING,
	/** Never verifies anything, whatever the signature. */
	REVOKED
}
