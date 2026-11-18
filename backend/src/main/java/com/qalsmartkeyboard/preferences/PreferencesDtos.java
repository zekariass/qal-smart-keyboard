package com.qalsmartkeyboard.preferences;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of {@code /me/preferences} and {@code /me/typing-languages} (contract schemas
 * {@code Preferences}, {@code TypingLanguage}, {@code TypingLanguagesDocument}). The same record serves as request and
 * response; absent optional members of a request mean "the default" (the PUT replaces the whole document).
 */
final class PreferencesDtos {

	static final String LANGUAGE = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

	private PreferencesDtos() {
	}

	/** {@code Preferences}. {@code updatedAt} is read-only: ignored on input. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PreferencesDto(@NotNull @Min(0) Integer revision,
			@Size(max = 35) @Pattern(regexp = LANGUAGE) String defaultSourceLanguage,
			@Pattern(regexp = "^(AUTO|MANUAL)$") String sourceDetectionMode,
			@Size(max = 35) @Pattern(regexp = LANGUAGE) String defaultDestinationLanguage,
			@Pattern(regexp = "^(DISABLED|MANUAL|PROMPT)$") String translationMode,
			@Pattern(regexp = "^(LITERAL|NATURAL|POLITE|PROFESSIONAL|FRIENDLY)$") String defaultTranslationStyle,
			Boolean preserveNamesInTranslation, @Pattern(regexp = "^(AUTO|MANUAL)$") String voiceInputLanguageMode,
			@Size(max = 35) @Pattern(regexp = LANGUAGE) String preferredVoiceLanguage,
			@Pattern(regexp = "^(GREGORIAN|ETHIOPIAN)$") String calendarSystem,
			@Pattern(regexp = "^(INTERNATIONAL|ETHIOPIAN)$") String timeNotation,
			@Pattern(regexp = "^(WESTERN|ETHIOPIC)$") String numeralStyle,
			@Pattern(regexp = "^(INFORMAL|FORMAL|HONORIFIC)$") String defaultFormality,
			@Pattern(regexp = "^(MASCULINE|FEMININE|PLURAL|UNKNOWN)$") String defaultAddresseeGender,
			@Pattern(regexp = "^(SERVER|PREFER_ON_DEVICE|ON_DEVICE_ONLY)$") String aiProcessingPreference,
			Boolean historyEnabled, @Min(1) @Max(365) Integer historyRetentionDays,
			@Size(max = 40) String keyboardTheme, Boolean hapticsEnabled, Boolean soundEnabled, Boolean analyticsOptIn,
			Instant updatedAt) {
	}

	/** {@code TypingLanguage}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TypingLanguageDto(@NotNull @Size(max = 35) @Pattern(regexp = LANGUAGE) String languageCode,
			@NotNull Boolean enabled, Boolean isPrimary, Integer sortOrder,
			@NotNull @Pattern(regexp = "^(DIRECT_SCRIPT_LAYOUT|PHONETIC_LATIN_TO_NATIVE|LATIN_PASSTHROUGH)$") String inputMode,
			@Size(max = 100) String transliterationProfileCode,
			@Pattern(regexp = "^(LIVE_COMPOSING|WORD_BOUNDARY|MANUAL)$") String conversionTrigger,
			Boolean showTransliterationCandidates,
			@Pattern(regexp = "^(PRESERVE_LATIN|SUGGEST_ONLY)$") String unknownTokenBehavior) {
	}

	/** {@code TypingLanguagesDocument}. */
	record TypingLanguagesDto(@NotNull @Min(0) Integer revision,
			@NotNull @Size(max = 20) List<@NotNull @Valid TypingLanguageDto> languages) {
	}
}
