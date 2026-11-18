package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DataPolicy;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.ProviderCapability;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.ProviderStatus;
import com.qalsmartkeyboard.aigateway.spi.ProviderType;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;
import com.qalsmartkeyboard.aigateway.spi.ResolvedProvider;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;

/** Registry filtering by status, adapter presence, data policy and capability (B5.1). No database. */
class ProviderRegistryTest {

	static final DataPolicy CLEAN = new DataPolicy(false, false, Set.of("eu"), true);
	static final DataPolicy ZDR = new DataPolicy(true, false, Set.of("eu"), true);
	static final RouteQuery EN_AM = RouteQuery.of(AiFeature.TRANSLATE, "en", "am");

	static ProviderCapability translate(String src, String dst, QualityTier tier, boolean auto, Integer max) {
		return new ProviderCapability(AiFeature.TRANSLATE, src, dst, tier, auto, max, "m");
	}

	static ProviderDescriptor provider(String code, ProviderStatus status, DataPolicy policy,
			ProviderCapability... caps) {
		return new ProviderDescriptor(code, code, ProviderType.MULTI, status, policy, List.of(caps));
	}

	static ProviderRegistry registry(List<ProviderDescriptor> descriptors, String... adapterCodes) {
		List<MockAiProvider> adapters = java.util.Arrays.stream(adapterCodes)
				.map(c -> new MockAiProvider(c, MockAiProvider.Behavior.ok())).toList();
		return new ProviderRegistry(() -> descriptors, adapters, Duration.ZERO, Clock.systemUTC());
	}

	static List<String> codes(List<ResolvedProvider> resolved) {
		return resolved.stream().map(ResolvedProvider::code).toList();
	}

	@Test
	void onlyActiveProvidersAreExposed() {
		ProviderCapability cap = translate("en", "am", QualityTier.BETA, false, null);
		var reg = registry(List.of(provider("a-active", ProviderStatus.ACTIVE, CLEAN, cap),
				provider("b-degraded", ProviderStatus.DEGRADED, CLEAN, cap),
				provider("c-disabled", ProviderStatus.DISABLED, CLEAN, cap)), "a-active", "b-degraded", "c-disabled");

		assertThat(codes(reg.resolve(EN_AM))).containsExactly("a-active");
		assertThat(reg.activeDescriptors()).extracting(ProviderDescriptor::code).containsExactly("a-active");
	}

	@Test
	void everyProviderDisabledMeansNothingResolves() {
		ProviderCapability cap = translate("en", "am", QualityTier.PRODUCTION, true, null);
		var reg = registry(List.of(provider("a", ProviderStatus.DISABLED, ZDR, cap)), "a");
		assertThat(reg.resolve(EN_AM)).isEmpty();
	}

	@Test
	void aRowWithoutAnAdapterIsNeverRoutable() {
		ProviderCapability cap = translate("en", "am", QualityTier.BETA, false, null);
		var reg = registry(List.of(provider("no-adapter", ProviderStatus.ACTIVE, CLEAN, cap)));
		assertThat(reg.resolve(EN_AM)).isEmpty();
		assertThat(reg.activeDescriptors()).isEmpty();
	}

	@Test
	void zeroRetentionRequirementExcludesProvidersWithoutZdr() {
		ProviderCapability cap = translate("en", "am", QualityTier.BETA, false, null);
		var reg = registry(List.of(provider("plain", ProviderStatus.ACTIVE, CLEAN, cap),
				provider("zdr", ProviderStatus.ACTIVE, ZDR, cap)), "plain", "zdr");

		assertThat(codes(reg.resolve(EN_AM))).containsExactly("plain", "zdr");
		assertThat(codes(reg.resolve(EN_AM.withDataPolicy(DataPolicyRequirement.ZERO_RETENTION)))).containsExactly("zdr");
	}

	@Test
	void aProviderThatTrainsOnCustomerDataIsNeverRoutedEvenIfActive() {
		ProviderCapability cap = translate("en", "am", QualityTier.BETA, false, null);
		DataPolicy trains = new DataPolicy(true, true, Set.of("eu"), true);
		var reg = registry(List.of(provider("trainer", ProviderStatus.ACTIVE, trains, cap)), "trainer");
		assertThat(reg.resolve(EN_AM)).isEmpty();
		assertThat(reg.resolve(EN_AM.withDataPolicy(DataPolicyRequirement.ZERO_RETENTION))).isEmpty();
	}

	@Test
	void aNonSelfHostedProviderNeedsASignedDpa() {
		ProviderCapability cap = translate("en", "am", QualityTier.BETA, false, null);
		DataPolicy noDpa = new DataPolicy(true, false, Set.of("eu"), false);
		var external = new ProviderDescriptor("ext", "ext", ProviderType.TRANSLATION, ProviderStatus.ACTIVE, noDpa,
				List.of(cap));
		var selfHosted = new ProviderDescriptor("self", "self", ProviderType.SELF_HOSTED, ProviderStatus.ACTIVE, noDpa,
				List.of(cap));
		var reg = registry(List.of(external, selfHosted), "ext", "self");
		assertThat(codes(reg.resolve(EN_AM))).containsExactly("self");
	}

	@Test
	void regionAllowListNeedsEveryDeclaredRegionInsideIt() {
		ProviderCapability cap = translate("en", "am", QualityTier.BETA, false, null);
		var reg = registry(List.of(provider("eu", ProviderStatus.ACTIVE, new DataPolicy(false, false, Set.of("eu"), true), cap),
				provider("eu-us", ProviderStatus.ACTIVE, new DataPolicy(false, false, Set.of("eu", "us"), true), cap),
				provider("undeclared", ProviderStatus.ACTIVE, new DataPolicy(false, false, Set.of(), true), cap)),
				"eu", "eu-us", "undeclared");

		RouteQuery euOnly = EN_AM.withDataPolicy(new DataPolicyRequirement(false, Set.of("EU")));
		assertThat(codes(reg.resolve(euOnly))).containsExactly("eu");
		assertThat(codes(reg.resolve(EN_AM))).containsExactly("eu", "eu-us", "undeclared");
	}

	@Test
	void capabilityMustMatchPairTierAndSize() {
		var reg = registry(List.of(
				provider("pair", ProviderStatus.ACTIVE, CLEAN, translate("en", "ti", QualityTier.PRODUCTION, false, null)),
				provider("experimental", ProviderStatus.ACTIVE, CLEAN,
						translate("en", "am", QualityTier.EXPERIMENTAL, false, null)),
				provider("small", ProviderStatus.ACTIVE, CLEAN, translate("en", "am", QualityTier.BETA, false, 100)),
				provider("any-source", ProviderStatus.ACTIVE, CLEAN, translate(null, "am", QualityTier.BETA, false, null))),
				"pair", "experimental", "small", "any-source");

		assertThat(codes(reg.resolve(EN_AM))).containsExactly("any-source", "small");
		assertThat(codes(reg.resolve(EN_AM.withInputCharacters(101)))).containsExactly("any-source");
		assertThat(codes(reg.resolve(EN_AM.withMinimumTier(QualityTier.EXPERIMENTAL)))).containsExactly("any-source",
				"experimental", "small");
		assertThat(codes(reg.resolve(EN_AM.withMinimumTier(QualityTier.PRODUCTION)))).isEmpty();
	}

	@Test
	void autoDetectedSourceNeedsADetectingProvider() {
		var reg = registry(List.of(
				provider("detects", ProviderStatus.ACTIVE, CLEAN, translate("en", "am", QualityTier.BETA, true, null)),
				provider("fixed", ProviderStatus.ACTIVE, CLEAN, translate("en", "am", QualityTier.BETA, false, null))),
				"detects", "fixed");
		assertThat(codes(reg.resolve(RouteQuery.of(AiFeature.TRANSLATE, null, "am")))).containsExactly("detects");
	}

	@Test
	void detectLanguageAndTransformFeaturesUseTheirOwnShape() {
		ProviderCapability detect = new ProviderCapability(AiFeature.DETECT_LANGUAGE, null, null, QualityTier.BETA, true,
				1000, "lid");
		ProviderCapability grammar = new ProviderCapability(AiFeature.GRAMMAR_CORRECTION, "am", null, QualityTier.BETA,
				false, null, "llm");
		var reg = registry(List.of(provider("p", ProviderStatus.ACTIVE, CLEAN, detect, grammar)), "p");

		assertThat(reg.resolve(RouteQuery.of(AiFeature.DETECT_LANGUAGE, null, null))).hasSize(1);
		assertThat(reg.resolve(RouteQuery.of(AiFeature.GRAMMAR_CORRECTION, "am", null))).hasSize(1);
		assertThat(reg.resolve(RouteQuery.of(AiFeature.GRAMMAR_CORRECTION, "ti", null))).isEmpty();
		assertThat(reg.resolve(RouteQuery.of(AiFeature.REWRITE, "am", null))).isEmpty();
		assertThat(reg.resolve("p", RouteQuery.of(AiFeature.GRAMMAR_CORRECTION, "am", null))).isPresent();
		assertThat(reg.resolve("other", RouteQuery.of(AiFeature.GRAMMAR_CORRECTION, "am", null))).isEmpty();
	}

	@Test
	void resolvedProviderCarriesTheBestCapabilityAndTheAdapter() {
		var reg = registry(List.of(provider("p", ProviderStatus.ACTIVE, CLEAN,
				translate("en", "am", QualityTier.BETA, false, null),
				translate("en", "am", QualityTier.PRODUCTION, false, null))), "p");
		ResolvedProvider r = reg.resolve(EN_AM).get(0);
		assertThat(r.capability().qualityTier()).isEqualTo(QualityTier.PRODUCTION);
		assertThat(r.adapter().code()).isEqualTo("p");
	}

	@Test
	void duplicateAdapterCodesFailFast() {
		assertThatThrownBy(() -> registry(List.of(), "x", "x")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void snapshotIsCachedForTheTtlAndStaleServedWhenReloadFails() {
		AtomicInteger loads = new AtomicInteger();
		AtomicBoolean failing = new AtomicBoolean();
		ProviderDescriptor d = provider("p", ProviderStatus.ACTIVE, CLEAN, translate("en", "am", QualityTier.BETA, false, null));
		MutableClock clock = new MutableClock();
		var reg = new ProviderRegistry(() -> {
			loads.incrementAndGet();
			if (failing.get()) {
				throw new IllegalStateException("db down");
			}
			return List.of(d);
		}, List.of(new MockAiProvider("p", MockAiProvider.Behavior.ok())), Duration.ofSeconds(30), clock);

		assertThat(reg.resolve(EN_AM)).hasSize(1);
		assertThat(reg.resolve(EN_AM)).hasSize(1);
		assertThat(loads).hasValue(1);

		clock.advance(Duration.ofSeconds(31));
		failing.set(true);
		assertThat(reg.resolve(EN_AM)).hasSize(1); // stale snapshot
		assertThat(loads).hasValue(2);
	}

	@Test
	void firstLoadFailurePropagates() {
		var reg = new ProviderRegistry(() -> {
			throw new IllegalStateException("db down");
		}, List.of(), Duration.ofSeconds(30), Clock.systemUTC());
		assertThatThrownBy(() -> reg.resolve(EN_AM)).isInstanceOf(IllegalStateException.class);
	}

	static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-11-18T00:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
