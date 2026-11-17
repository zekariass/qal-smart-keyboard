package com.qalsmartkeyboard.platform;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of the Problem {@code errors} array. {@code message} must be a static, safe sentence:
 * never include the rejected value or any user-supplied text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // the contract's `field` is a string, never null: omit when absent
public record ApiFieldError(String field, String code, String message) {
}
