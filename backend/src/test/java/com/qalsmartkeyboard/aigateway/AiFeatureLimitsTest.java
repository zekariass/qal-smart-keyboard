package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/** ADR 0027: per-feature time budgets and output token caps, defaults and configuration. */
class AiFeatureLimitsTest {

	@EnableConfigurationProperties(AiGatewayProperties.class)
	static class Properties {
	}

	static AiGatewayProperties.Pipeline pipeline(Map<String, AiGatewayProperties.FeatureTimeouts> timeouts) {
		return new AiGatewayProperties.Pipeline(Duration.ofSeconds(8), false, Duration.ofSeconds(30), 0.9, 5000, 1000,
				timeouts);
	}

	@Test
	void theLongFormFeaturesHaveLongerBudgetsAndTheOthersKeepTheKeyboards() {
		AiPipeline.Settings s = new AiPipeline.Settings(Duration.ofSeconds(8), false, 5000, 1000,
				pipeline(null).resolvedFeatureTimeouts());
		assertThat(s.deadlineFor(AiFeature.DRAFT_DOCUMENT)).isEqualTo(Duration.ofSeconds(30));
		assertThat(s.attemptTimeoutFor(AiFeature.DRAFT_DOCUMENT)).isEqualTo(Duration.ofSeconds(20));
		// ADR 0048: a chat turn gets the e-mail budget (20 s per attempt, 30 s in total)
		assertThat(s.deadlineFor(AiFeature.AI_CHAT)).isEqualTo(Duration.ofSeconds(30));
		assertThat(s.attemptTimeoutFor(AiFeature.AI_CHAT)).isEqualTo(Duration.ofSeconds(20));
		for (AiFeature f : new AiFeature[] { AiFeature.SUMMARIZE, AiFeature.EXPLAIN_TEXT, AiFeature.SMART_REPLY }) {
			assertThat(s.deadlineFor(f)).as(f.name()).isEqualTo(Duration.ofSeconds(20));
			assertThat(s.attemptTimeoutFor(f)).as(f.name()).isEqualTo(Duration.ofSeconds(12));
		}
		assertThat(s.deadlineFor(AiFeature.TRANSLATE)).isEqualTo(Duration.ofSeconds(12));
		assertThat(s.attemptTimeoutFor(AiFeature.TRANSLATE)).isEqualTo(Duration.ofSeconds(8));
		for (AiFeature f : new AiFeature[] { AiFeature.GRAMMAR_CORRECTION, AiFeature.REWRITE, AiFeature.TONE_ADJUST,
				AiFeature.FORMAT_TEXT }) {
			assertThat(s.deadlineFor(f)).as(f.name()).isEqualTo(Duration.ofSeconds(10));
			assertThat(s.attemptTimeoutFor(f)).as(f.name()).isEqualTo(Duration.ofSeconds(6));
		}
		for (AiFeature f : new AiFeature[] { AiFeature.DETECT_LANGUAGE, AiFeature.SCRIPT_CONVERSION }) {
			assertThat(s.deadlineFor(f)).as(f.name()).isEqualTo(Duration.ofSeconds(8));
			assertThat(s.attemptTimeoutFor(f)).as(f.name()).isNull(); // the router's 4 s
		}
		// the B5.3 constructor has the same defaults
		assertThat(new AiPipeline.Settings(Duration.ofSeconds(8), false, 5000, 1000).featureTimeouts())
				.isEqualTo(AiPipeline.DEFAULT_FEATURE_TIMEOUTS);
	}

	@Test
	void configuredBudgetsOverrideOrAddFeaturesAndBadOnesFailStartup() {
		Map<AiFeature, AiPipeline.Timeouts> t = pipeline(Map.of(
				"draft-document", new AiGatewayProperties.FeatureTimeouts(null, Duration.ofSeconds(45)),
				"TRANSLATE", new AiGatewayProperties.FeatureTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(6))))
				.resolvedFeatureTimeouts();
		assertThat(t.get(AiFeature.DRAFT_DOCUMENT))
				.isEqualTo(new AiPipeline.Timeouts(Duration.ofSeconds(20), Duration.ofSeconds(45)));
		assertThat(t.get(AiFeature.TRANSLATE))
				.isEqualTo(new AiPipeline.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(6)));
		assertThatThrownBy(() -> pipeline(Map.of("NOPE", new AiGatewayProperties.FeatureTimeouts(null, null)))
				.resolvedFeatureTimeouts()).hasMessageContaining("unknown AI feature");
		assertThatThrownBy(() -> pipeline(Map.of("SUMMARIZE",
				new AiGatewayProperties.FeatureTimeouts(Duration.ofSeconds(30), Duration.ofSeconds(10))))
				.resolvedFeatureTimeouts()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> pipeline(Map.of("SCRIPT_CONVERSION",
				new AiGatewayProperties.FeatureTimeouts(Duration.ofSeconds(3), null))).resolvedFeatureTimeouts())
				.isInstanceOf(IllegalArgumentException.class); // no default to complete it
	}

	@Test
	void budgetsAndTokenCapsBindFromProperties() {
		new ApplicationContextRunner().withUserConfiguration(Properties.class)
				.withPropertyValues("qal.ai.pipeline.feature-timeouts.DRAFT_DOCUMENT.deadline=40s",
						"qal.ai.pipeline.feature-timeouts.SMART_REPLY.attempt-timeout=9s",
						"qal.ai.openai.max-output-tokens-by-feature.SUMMARIZE=2048")
				.run(ctx -> {
					AiGatewayProperties p = ctx.getBean(AiGatewayProperties.class);
					Map<AiFeature, AiPipeline.Timeouts> t = p.pipeline().resolvedFeatureTimeouts();
					assertThat(t.get(AiFeature.DRAFT_DOCUMENT).deadline()).isEqualTo(Duration.ofSeconds(40));
					assertThat(t.get(AiFeature.SMART_REPLY).attemptTimeout()).isEqualTo(Duration.ofSeconds(9));
					assertThat(p.openai().featureMaxOutputTokens()).containsEntry(AiFeature.DRAFT_DOCUMENT, 8192)
							.containsEntry(AiFeature.AI_CHAT, 8192).containsEntry(AiFeature.SUMMARIZE, 2048).hasSize(3);
				});
		new ApplicationContextRunner().withUserConfiguration(Properties.class).run(ctx -> assertThat(
				ctx.getBean(AiGatewayProperties.class).openai().featureMaxOutputTokens())
				.containsOnly(Map.entry(AiFeature.DRAFT_DOCUMENT, 8192), Map.entry(AiFeature.AI_CHAT, 8192)));
	}
}
