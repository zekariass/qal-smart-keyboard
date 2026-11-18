package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;

/**
 * Credits usable at an instant: the sum of the remainders of lots that have not expired then.
 *
 * @param exact   exact value, 6 decimal places (what the engine spends)
 * @param display what the user is shown, rounded down to whole credits (SD 15.4)
 */
public record CreditBalance(BigDecimal exact, long display) {
}
