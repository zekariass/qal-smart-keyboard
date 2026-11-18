package com.qalsmartkeyboard.aigateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates.Snapshot;
import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates.Template;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/** Template selection (language scope, model family), rendering and the prompt values (SD 11.4, 11.5). */
class PromptTemplatesTest {

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T08:00:00Z"), ZoneOffset.UTC);

	final PromptRenderer renderer = new PromptRenderer();

	static Template template(String feature, int version, String language, String family, String body) {
		return new Template(UUID.randomUUID(), feature, version, language, family, body);
	}

	@Test
	void theMostSpecificLanguageThenFamilyWins() {
		Template any = template("TRANSLATE", 1, "*", "*", "generic");
		Template am = template("TRANSLATE", 2, "am", "*", "amharic");
		Template amGpt = template("TRANSLATE", 3, "am", "fam-x", "amharic for family x");
		Template anyGpt = template("TRANSLATE", 4, "*", "fam-x", "generic for family x");
		Template grammar = template("GRAMMAR_CORRECTION", 1, "*", "*", "grammar");
		PromptTemplates t = new PromptTemplates(() -> new Snapshot(List.of(any, am, amGpt, anyGpt, grammar),
				Map.of("p1/model-a", "fam-x", "p2/model-b", "model-b")), renderer, Duration.ZERO, CLOCK);

		assertThat(t.select("TRANSLATE", "am", "p1", "model-a")).contains(amGpt);
		assertThat(t.select("TRANSLATE", "am", "p2", "model-b")).contains(am);
		assertThat(t.select("TRANSLATE", "om", "p1", "model-a")).contains(anyGpt);
		assertThat(t.select("TRANSLATE", "om", "p2", "model-b")).contains(any);
		assertThat(t.select("GRAMMAR_CORRECTION", "am", null, null)).contains(grammar);
		assertThat(t.select("REWRITE", "am", "p1", "model-a")).isEmpty();
	}

	@Test
	void aTemplateWithAnUnknownPlaceholderIsNeverServed() {
		Template bad = template("TRANSLATE", 5, "*", "*", "Translate this: {{text}}");
		PromptTemplates t = new PromptTemplates(() -> new Snapshot(List.of(bad), Map.of()), renderer, Duration.ZERO,
				CLOCK);
		assertThat(t.select("TRANSLATE", "am", null, null)).isEmpty();
		assertThatThrownBy(() -> renderer.render(bad.body(), Map.of())).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("text");
	}

	@Test
	void aFailedReloadServesTheLastSnapshot() {
		AtomicInteger loads = new AtomicInteger();
		Template any = template("TRANSLATE", 1, "*", "*", "generic");
		PromptTemplates t = new PromptTemplates(() -> {
			if (loads.incrementAndGet() > 1) {
				throw new IllegalStateException("database down");
			}
			return new Snapshot(List.of(any), Map.of());
		}, renderer, Duration.ZERO, CLOCK);
		assertThat(t.select("TRANSLATE", "am", null, null)).contains(any);
		assertThat(t.select("TRANSLATE", "am", null, null)).contains(any);
		assertThat(loads).hasValue(2);
	}

	@Test
	void renderingFillsAddresseeCulturalAndEthiopianCalendarContext() {
		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(AiFeature.TRANSLATE, null, null,
				"NATURAL", null, "en", "English", "am", "Amharic", null, null, Addressee.Formality.HONORIFIC,
				Addressee.Gender.UNKNOWN, "GREGORIAN", "ETHIOPIAN", "WESTERN", true, true, LocalDate.of(2026, 11, 18)));
		String body = """
				Translate from {{sourceLanguageName}} to {{destinationLanguageName}} ({{style}}).
				{{addresseeInstruction}}
				{{culturalInstruction}}
				{{calendarContext}} Missing: [{{tone}}]""";
		String out = renderer.render(body, values);
		assertThat(out).contains("Translate from English to Amharic (NATURAL).")
				.contains("honorific, respectful forms (Amharic እርስዎ")
				.doesNotContain("gender is unknown") // honorific forms do not inflect for gender
				.contains("Ethiopian clock").contains("Gregorian calendar, converting Ethiopian")
				.contains("Today is 2026-11-18 in the Gregorian calendar, which is 9 Hidar 2019 in the Ethiopian "
						+ "calendar (ኅዳር 9 ቀን 2019 ዓ.ም.).")
				.contains("Missing: []");
	}

	@Test
	void addresseeSentencesCoverEveryGender() {
		assertThat(PromptValues.addressee(Addressee.Formality.INFORMAL, Addressee.Gender.FEMININE))
				.contains("informally").contains("a woman");
		assertThat(PromptValues.addressee(Addressee.Formality.FORMAL, Addressee.Gender.PLURAL))
				.contains("formally").contains("several people");
		assertThat(PromptValues.addressee(Addressee.Formality.FORMAL, Addressee.Gender.UNKNOWN))
				.contains("most neutral");
		assertThat(PromptValues.addressee(null, null)).isEmpty();
	}

	@Test
	void everyPlaceholderIsKnownToTheRenderer() {
		String all = String.join(" ", PromptRenderer.PLACEHOLDERS.stream().map(p -> "{{" + p + "}}").toList());
		assertThat(renderer.unknownPlaceholders(all)).isEmpty();
		assertThat(PromptRenderer.PLACEHOLDERS).doesNotContain("text", "input", "userText");
	}
}
