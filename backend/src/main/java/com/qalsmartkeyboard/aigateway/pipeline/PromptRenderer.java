package com.qalsmartkeyboard.aigateway.pipeline;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {@code ai_prompt_templates.template_body} (B5.3, SD 11.4). The syntax is {@code {{name}}} with names from
 * {@link #PLACEHOLDERS} only; a missing value renders as an empty string.
 *
 * <p><b>User text is never a placeholder.</b> There is no {@code {{text}}}: the adapter sends the user's text next to
 * the rendered instructions as delimited, untrusted data (SPI {@code TransformRequest}), so nothing a user types can
 * become part of the instructions. Every value rendered here comes from enums, language codes, the server clock or
 * the language catalogue. A template that uses an unknown placeholder is invalid and is never served.
 */
public final class PromptRenderer {

	/** Every name a template may use. */
	public static final Set<String> PLACEHOLDERS = Set.of(
			// task
			"feature", "mode", "tone", "style", "targetLength", "explainIn",
			// languages (codes and English names from supported_languages)
			"sourceLanguage", "sourceLanguageName", "destinationLanguage", "destinationLanguageName", "language",
			"languageName",
			// addressee (SD 11.5) and a ready-made sentence for it
			"formality", "gender", "addresseeInstruction",
			// cultural options (SD 11.5) and a ready-made sentence for them
			"calendar", "timeNotation", "numerals", "adaptIdioms", "preserveNames", "culturalInstruction",
			// Ethiopian calendar context: today in the user's time zone
			"todayGregorian", "todayEthiopian", "todayEthiopianAmharic", "calendarContext");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");

	/** The placeholders a body uses that are not allowed (empty = valid). */
	public Set<String> unknownPlaceholders(String body) {
		Set<String> unknown = new LinkedHashSet<>();
		Matcher m = PLACEHOLDER.matcher(body);
		while (m.find()) {
			if (!PLACEHOLDERS.contains(m.group(1))) {
				unknown.add(m.group(1));
			}
		}
		return unknown;
	}

	/** @throws IllegalArgumentException the body uses a placeholder outside {@link #PLACEHOLDERS} */
	public String render(String body, Map<String, String> values) {
		Set<String> unknown = unknownPlaceholders(body);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("template uses unknown placeholders " + unknown);
		}
		Matcher m = PLACEHOLDER.matcher(body);
		StringBuilder out = new StringBuilder(body.length() + 128);
		while (m.find()) {
			String value = values.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
		}
		m.appendTail(out);
		return out.toString().strip();
	}
}
