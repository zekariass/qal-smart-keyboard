package com.qalsmartkeyboard.privacy;

import java.util.UUID;

/**
 * Published, inside the recording transaction (a listener's writes commit or roll back with the decision), after every
 * consent decision. Other modules stop dependent processing on {@code granted == false}: preferences switches
 * {@code aiHistoryEnabled} off when {@code SAVE_AI_HISTORY} is withdrawn; billing will cancel mandates on
 * {@code PAYMENT_MANDATE}. Existing data is not deleted by this event: the contract says withdrawing
 * {@code SAVE_AI_HISTORY} "stops new history writes and offers deletion", so deletion is the person's explicit choice.
 *
 * @param consentId the appended {@code user_consents.id}
 */
public record ConsentChanged(UUID userId, ConsentType type, boolean granted, UUID consentId) {
}
