/**
 * Per-user envelope encryption of user content (SD §18, §19.4, Review D17, plan B1.5, ADR 0005).
 *
 * <p>This package is the platform module's {@code crypto} named interface. Other modules use
 * {@link com.qalsmartkeyboard.platform.crypto.UserDataEncryption} to seal and open {@code *_ciphertext}
 * columns and, in {@code privacy}, to crypto-shred a user on account deletion. Implementation and the KMS
 * port live in the {@code internal} and {@code kms} sub-packages and are not part of the API.
 */
@NamedInterface("crypto")
package com.qalsmartkeyboard.platform.crypto;

import org.springframework.modulith.NamedInterface;
