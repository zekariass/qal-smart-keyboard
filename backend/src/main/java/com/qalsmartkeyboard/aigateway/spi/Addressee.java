package com.qalsmartkeyboard.aigateway.spi;

/** Who the text is addressed to (SD 11.5); every field optional and never persisted by the SPI. */
public record Addressee(Formality formality, Gender gender) {

	public enum Formality {
		INFORMAL, FORMAL, HONORIFIC
	}

	public enum Gender {
		MASCULINE, FEMININE, PLURAL, UNKNOWN
	}

	public static final Addressee UNSPECIFIED = new Addressee(null, null);
}
