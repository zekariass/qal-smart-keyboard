package com.qalsmartkeyboard.aigateway.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Versioned prompt templates from {@code ai_prompt_templates} (V1; SD 11.4: versioned per feature, language scope and
 * model family). Only rows with {@code is_active} are served, which the V1 CHECK allows only for
 * {@code evaluation_status = 'APPROVED'}, and the partial unique index allows one per (feature, language scope, model
 * family). Promotion (evaluation run, approval, activation) is an operator action; this step seeds nothing.
 *
 * <p><b>Selection</b> for (feature, language, provider model): rows whose {@code language_scope} is the language or
 * {@code '*'} and whose {@code model_family} is the model's family or {@code '*'}; the exact language beats
 * {@code '*'}, then the exact family beats {@code '*'}. The family of a model is
 * {@code ai_models.configuration ->> 'modelFamily'}, else its {@code model_code}. A row with an unknown placeholder is
 * skipped (logged once per load with its id and version, never its body).
 *
 * <p>The whole table is a snapshot cached for {@code qal.ai.pipeline.catalog-cache-ttl}; the stale snapshot is served
 * when a reload fails.
 */
public final class PromptTemplates {

	private static final Logger log = LoggerFactory.getLogger(PromptTemplates.class);

	/** One active template. {@code body} is operator-written text, never user content. */
	public record Template(UUID id, String featureCode, int version, String languageScope, String modelFamily,
			String body) {

		@Override
		public String toString() {
			return "Template[" + featureCode + " v" + version + " " + languageScope + "/" + modelFamily + "]";
		}
	}

	/** Active templates and model families keyed {@code providerCode + '/' + modelCode}. */
	public record Snapshot(List<Template> templates, Map<String, String> modelFamilies) {
	}

	private final CachedSnapshot<Snapshot> snapshot;

	public PromptTemplates(java.util.function.Supplier<Snapshot> source, PromptRenderer renderer, Duration ttl,
			Clock clock) {
		this.snapshot = new CachedSnapshot<>("prompt template", () -> validated(source.get(), renderer), ttl, clock);
	}

	public static java.util.function.Supplier<Snapshot> jdbc(JdbcClient jdbc) {
		return () -> {
			List<Template> templates = jdbc.sql("""
					SELECT id, feature_code, template_version, language_scope, model_family, template_body
					  FROM ai_prompt_templates WHERE is_active = TRUE AND evaluation_status = 'APPROVED'
					""").query((rs, n) -> new Template(rs.getObject("id", UUID.class), rs.getString("feature_code"),
					rs.getInt("template_version"), rs.getString("language_scope"), rs.getString("model_family"),
					rs.getString("template_body"))).list();
			Map<String, String> families = new HashMap<>();
			jdbc.sql("""
					SELECT p.provider_code, m.model_code, COALESCE(NULLIF(m.configuration ->> 'modelFamily', ''),
					       m.model_code) AS family
					  FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
					""").query(rs -> {
				families.put(rs.getString("provider_code") + "/" + rs.getString("model_code"), rs.getString("family"));
			});
			return new Snapshot(templates, families);
		};
	}

	private static Snapshot validated(Snapshot raw, PromptRenderer renderer) {
		List<Template> ok = new ArrayList<>();
		for (Template t : raw.templates()) {
			Set<String> unknown = renderer.unknownPlaceholders(t.body());
			if (unknown.isEmpty()) {
				ok.add(t);
			} else {
				log.warn("Prompt template {} ({} v{}) is skipped: unknown placeholders {}", t.id(), t.featureCode(),
						t.version(), unknown);
			}
		}
		return new Snapshot(List.copyOf(ok), Map.copyOf(raw.modelFamilies()));
	}

	/**
	 * The template for a call, or empty when none is active (the adapter then gets no instructions, which the SPI
	 * allows for adapters with built-in prompts; the pipeline records no template id).
	 *
	 * @param language     the language that decides the wording (translation: destination; transform: the text's)
	 * @param providerCode the provider about to be called; with {@code modelCode} it gives the model family
	 */
	public Optional<Template> select(String featureCode, String language, String providerCode, String modelCode) {
		Snapshot s = snapshot.get();
		String family = providerCode == null || modelCode == null ? null
				: s.modelFamilies().get(providerCode + "/" + modelCode);
		return s.templates().stream().filter(t -> t.featureCode().equals(featureCode))
				.filter(t -> "*".equals(t.languageScope()) || t.languageScope().equals(language))
				.filter(t -> "*".equals(t.modelFamily()) || t.modelFamily().equals(family))
				.min(Comparator.comparing((Template t) -> "*".equals(t.languageScope()))
						.thenComparing(t -> "*".equals(t.modelFamily())));
	}
}
