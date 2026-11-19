package com.qalsmartkeyboard.aigateway.pipeline;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/**
 * The values a prompt template is rendered with (see {@link PromptRenderer#PLACEHOLDERS}): option codes, language
 * names, an addressee sentence (SD 11.5: Amharic and Tigrinya inflect for the addressee's gender, number and honorific
 * status), a cultural-options sentence (calendar, Ethiopian clock, numerals, idioms) and today's date in both
 * calendars in the user's time zone, so relative dates and conversions ("tomorrow at 3 o'clock") can be resolved.
 * Every value is derived from enums, codes, the catalogue or the clock: never from user text.
 */
public final class PromptValues {

	private PromptValues() {
	}

	/**
	 * Inputs, already resolved (request, then destination profile, then preferences). Language arguments are codes and
	 * English names; null = not applicable.
	 *
	 * @param outputLanguage     the language the result must be written in (ADR 0027); null = the destination language
	 *                           (translation), else the language of the text
	 * @param count              SMART_REPLY: number of replies; null otherwise
	 * @param variation          0 = first answer, higher = a different version (regenerate); null = not applicable
	 */
	public record Inputs(AiFeature feature, String mode, String tone, String style, String targetLength,
			String sourceLanguage, String sourceLanguageName, String destinationLanguage, String destinationLanguageName,
			String language, String languageName, Addressee.Formality formality, Addressee.Gender gender,
			String calendar, String timeNotation, String numerals, boolean adaptIdioms, boolean preserveNames,
			LocalDate today, String outputLanguage, String outputLanguageName, Integer count, Integer variation) {

		/** The B5.3 shape: no explicit output language, count or variation. */
		public Inputs(AiFeature feature, String mode, String tone, String style, String targetLength,
				String sourceLanguage, String sourceLanguageName, String destinationLanguage,
				String destinationLanguageName, String language, String languageName, Addressee.Formality formality,
				Addressee.Gender gender, String calendar, String timeNotation, String numerals, boolean adaptIdioms,
				boolean preserveNames, LocalDate today) {
			this(feature, mode, tone, style, targetLength, sourceLanguage, sourceLanguageName, destinationLanguage,
					destinationLanguageName, language, languageName, formality, gender, calendar, timeNotation, numerals,
					adaptIdioms, preserveNames, today, null, null, null, null);
		}
	}

	public static Map<String, String> of(Inputs in) {
		Map<String, String> v = new HashMap<>();
		put(v, "feature", in.feature().name());
		put(v, "mode", in.mode());
		put(v, "tone", in.tone());
		put(v, "style", in.style());
		put(v, "targetLength", in.targetLength());
		put(v, "sourceLanguage", in.sourceLanguage());
		put(v, "sourceLanguageName", in.sourceLanguageName());
		put(v, "destinationLanguage", in.destinationLanguage());
		put(v, "destinationLanguageName", in.destinationLanguageName());
		put(v, "language", in.language());
		put(v, "languageName", in.languageName());
		put(v, "formality", in.formality() == null ? null : in.formality().name());
		put(v, "gender", in.gender() == null ? null : in.gender().name());
		put(v, "addresseeInstruction", addressee(in.formality(), in.gender()));
		put(v, "calendar", in.calendar());
		put(v, "timeNotation", in.timeNotation());
		put(v, "numerals", in.numerals());
		put(v, "adaptIdioms", Boolean.toString(in.adaptIdioms()));
		put(v, "preserveNames", Boolean.toString(in.preserveNames()));
		put(v, "culturalInstruction", cultural(in));
		String out = first(in.outputLanguage(), in.destinationLanguage(), in.language());
		String outName = in.outputLanguage() != null ? in.outputLanguageName()
				: in.destinationLanguage() != null ? in.destinationLanguageName() : in.languageName();
		put(v, "outputLanguage", out);
		put(v, "outputLanguageName", outName);
		put(v, "outputLanguageInstruction", outName != null && !outName.isBlank()
				? "Write the result in " + outName + " (" + out + ")."
				: "Write the result in the same language as the input text.");
		put(v, "count", in.count() == null ? null : in.count().toString());
		put(v, "variation", in.variation() == null ? null : in.variation().toString());
		if (in.today() != null) {
			EthiopianDate et = EthiopianDate.from(in.today());
			put(v, "todayGregorian", in.today().toString());
			put(v, "todayEthiopian", et.formatLatin());
			put(v, "todayEthiopianAmharic", et.formatAmharic());
			put(v, "calendarContext", "Today is " + in.today() + " in the Gregorian calendar, which is "
					+ et.formatLatin() + " in the Ethiopian calendar (" + et.formatAmharic() + ").");
		}
		return v;
	}

	/** One or two sentences on how to address the reader; empty when nothing is known. */
	static String addressee(Addressee.Formality formality, Addressee.Gender gender) {
		List<String> s = new ArrayList<>();
		if (formality != null) {
			s.add(switch (formality) {
				case INFORMAL -> "Address the reader informally, with familiar second-person forms.";
				case FORMAL -> "Address the reader formally, with polite second-person forms.";
				case HONORIFIC -> "Address the reader with honorific, respectful forms (Amharic እርስዎ, Tigrinya ንስኹም).";
			});
		}
		if (gender != null && formality != Addressee.Formality.HONORIFIC) {
			s.add(switch (gender) {
				case MASCULINE -> "The addressee is a man: use masculine forms where the language inflects for gender.";
				case FEMININE -> "The addressee is a woman: use feminine forms where the language inflects for gender.";
				case PLURAL -> "The text addresses several people: use plural second-person forms.";
				case UNKNOWN -> "The addressee's gender is unknown: use the most neutral acceptable form.";
			});
		}
		return String.join(" ", s);
	}

	/** Calendar, clock, numeral, idiom and name instructions (SD 11.5). */
	static String cultural(Inputs in) {
		List<String> s = new ArrayList<>();
		if ("ETHIOPIAN".equals(in.calendar())) {
			s.add("Write dates in the Ethiopian calendar, converting Gregorian dates.");
		} else if ("GREGORIAN".equals(in.calendar())) {
			s.add("Write dates in the Gregorian calendar, converting Ethiopian calendar dates.");
		}
		if ("ETHIOPIAN".equals(in.timeNotation())) {
			s.add("Write clock times in the Ethiopian clock, which counts hours from 06:00 and 18:00 "
					+ "(07:00 is 1 o'clock, 06:00 is 12 o'clock).");
		} else if ("INTERNATIONAL".equals(in.timeNotation())) {
			s.add("Write clock times in international notation; an Ethiopian clock hour is six hours behind "
					+ "(1 o'clock in the morning, Ethiopian, is 07:00).");
		}
		if ("ETHIOPIC".equals(in.numerals())) {
			s.add("Write numbers with Ethiopic numerals (፩, ፪, ፫).");
		} else if ("WESTERN".equals(in.numerals())) {
			s.add("Write numbers with Western digits.");
		}
		s.add(in.adaptIdioms() ? "Render idioms by their meaning rather than word for word."
				: "Keep idioms close to the original wording.");
		if (in.preserveNames()) {
			s.add("Keep personal names, brand names, URLs and e-mail addresses unchanged.");
		}
		return String.join(" ", s);
	}

	private static String first(String... values) {
		for (String s : values) {
			if (s != null) {
				return s;
			}
		}
		return null;
	}

	private static void put(Map<String, String> v, String key, String value) {
		if (value != null) {
			v.put(key, value);
		}
	}
}
