package com.qalsmartkeyboard.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.configuration.ConfigRepository.ConfigRow;
import com.qalsmartkeyboard.configuration.ConfigRepository.FlagRow;

class ClientConfigServiceTest {

	static class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-17T10:00:00Z");

		@Override
		public ZoneOffset getZone() {
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

	ConfigRepository repo = mock(ConfigRepository.class);
	MutableClock clock = new MutableClock();
	ClientConfigService service;

	@BeforeEach
	void setUp() {
		service = new ClientConfigService(repo, new ConfigProperties(Duration.ofSeconds(30)), clock);
	}

	static AppVersion v(String s) {
		return AppVersion.parse(s).orElseThrow();
	}

	static FlagRow flag(String code, boolean on, int pct, String scope, String minVersion, String targeting) {
		return new FlagRow(code, on, pct, scope, minVersion, targeting);
	}

	static ConfigRow cfg(String key, String scope, int version, String json) {
		return new ConfigRow(key, scope, version, json);
	}

	@Test
	void noRowsMeansNoMinimumAndEmptyMaps() {
		when(repo.flags()).thenReturn(List.of());
		when(repo.activeClientConfigs()).thenReturn(List.of());
		ClientConfigDto dto = service.resolve(ClientPlatform.ANDROID, v("1.0.0"), null);
		assertThat(dto.minSupportedAppVersion()).isEqualTo("0.0.0");
		assertThat(dto.recommendedAppVersion()).isNull();
		assertThat(dto.configVersion()).isZero();
		assertThat(dto.flags()).isEmpty();
		assertThat(dto.values()).isEmpty();
		assertThat(dto.serverTime()).isEqualTo(clock.now);
		assertThat(service.minSupported(null)).isEqualTo(v("0.0.0"));
	}

	@Test
	void platformRowOverridesAllRowPerKeyAndBackendIsNeverExposed() {
		when(repo.flags()).thenReturn(List.of());
		// the repository already filters BACKEND out in SQL; a stray BACKEND row must still not apply
		when(repo.activeClientConfigs()).thenReturn(List.of(
				cfg("minSupportedAppVersion", "ALL", 1, "\"1.0.0\""),
				cfg("minSupportedAppVersion", "ANDROID", 3, "\"2.5.0\""),
				cfg("recommendedAppVersion", "ALL", 2, "\"2.6.0\""),
				cfg("typing.maxSuggestions", "ALL", 4, "5"),
				cfg("typing.maxSuggestions", "IOS", 2, "3"),
				cfg("ui.banner", "ALL", 1, "{\"text\":\"hi\",\"tags\":[1,2]}"),
				cfg("secret", "BACKEND", 9, "\"x\"")));

		ClientConfigDto android = service.resolve(ClientPlatform.ANDROID, v("2.5.0"), null);
		assertThat(android.minSupportedAppVersion()).isEqualTo("2.5.0");
		assertThat(android.recommendedAppVersion()).isEqualTo("2.6.0");
		assertThat(android.values()).containsOnlyKeys("typing.maxSuggestions", "ui.banner");
		assertThat(android.values().get("typing.maxSuggestions")).isEqualTo(5);
		assertThat(android.values().get("ui.banner")).isEqualTo(java.util.Map.of("text", "hi", "tags", List.of(1, 2)));
		assertThat(android.configVersion()).isEqualTo(4); // ANDROID min row 3, ALL rows up to 4

		ClientConfigDto ios = service.resolve(ClientPlatform.IOS, v("1.0.0"), null);
		assertThat(ios.minSupportedAppVersion()).isEqualTo("1.0.0");
		assertThat(ios.values().get("typing.maxSuggestions")).isEqualTo(3);

		ClientConfigDto unknown = service.resolve(null, null, null);
		assertThat(unknown.minSupportedAppVersion()).isEqualTo("1.0.0");
		assertThat(service.minSupported(ClientPlatform.ANDROID)).isEqualTo(v("2.5.0"));
		assertThat(service.minSupported(ClientPlatform.WEB)).isEqualTo(v("1.0.0"));
	}

	@Test
	void invalidMinimumValueIsIgnored() {
		when(repo.flags()).thenReturn(List.of());
		when(repo.activeClientConfigs()).thenReturn(List.of(cfg("minSupportedAppVersion", "ALL", 1, "42")));
		assertThat(service.minSupported(ClientPlatform.IOS)).isEqualTo(v("0.0.0"));
	}

	@Test
	void flagRules() {
		when(repo.activeClientConfigs()).thenReturn(List.of());
		when(repo.flags()).thenReturn(List.of(
				flag("off", false, 100, "ALL", null, "{}"),
				flag("on", true, 100, "ALL", null, "{}"),
				flag("zero", true, 0, "ALL", null, "{}"),
				flag("android_only", true, 100, "ANDROID", null, "{}"),
				flag("needs_2", true, 100, "ALL", "2.0.0", "{}"),
				flag("bad_min", true, 100, "ALL", "garbage", "{}"),
				flag("targeted", true, 100, "ALL", null, "{\"markets\":[\"ET\"]}"),
				flag("empty_targeting", true, 100, "ALL", null, "{\"markets\":[],\"plans\":null}"),
				flag("partial", true, 50, "ALL", null, "{}")));

		var android2 = service.resolve(ClientPlatform.ANDROID, v("2.0.0"), null).flags();
		assertThat(android2).containsEntry("off", false).containsEntry("on", true).containsEntry("zero", false)
				.containsEntry("android_only", true).containsEntry("needs_2", true).containsEntry("bad_min", false)
				.containsEntry("targeted", false).containsEntry("empty_targeting", true)
				.containsEntry("partial", false); // no stable key yet: partial rollout stays off

		var iosOld = service.resolve(ClientPlatform.IOS, v("1.9.9"), null).flags();
		assertThat(iosOld).containsEntry("android_only", false).containsEntry("needs_2", false);

		var unknown = service.resolve(null, null, null).flags();
		assertThat(unknown).containsEntry("on", true).containsEntry("android_only", false)
				.containsEntry("needs_2", false);
		assertThat(service.resolve(ClientPlatform.WEB, v("2.0.0-beta.1"), null).flags()).containsEntry("needs_2", false);
	}

	@Test
	void partialRolloutIsDeterministicPerFlagAndSubjectAndRoughlyProportional() {
		assertThat(ClientConfigService.inRollout("f", 100, null)).isTrue();
		assertThat(ClientConfigService.inRollout("f", 0, "u1")).isFalse();
		assertThat(ClientConfigService.inRollout("f", 50, null)).isFalse();
		for (int i = 0; i < 20; i++) {
			String subject = "user-" + i;
			assertThat(ClientConfigService.inRollout("f", 37, subject))
					.isEqualTo(ClientConfigService.inRollout("f", 37, subject));
			assertThat(ClientConfigService.bucket("f", subject)).isBetween(0, 99);
		}
		int in = 0;
		for (int i = 0; i < 10_000; i++) {
			if (ClientConfigService.inRollout("flag.x", 30, "subject-" + i)) {
				in++;
			}
		}
		assertThat(in).isBetween(2700, 3300);
		// monotone: a subject admitted at 30% stays admitted at 60%
		for (int i = 0; i < 500; i++) {
			String s = "m-" + i;
			if (ClientConfigService.inRollout("flag.x", 30, s)) {
				assertThat(ClientConfigService.inRollout("flag.x", 60, s)).isTrue();
			}
		}
		// the same subject lands in different buckets for different flags (salted by flag code)
		boolean differs = false;
		for (int i = 0; i < 50 && !differs; i++) {
			differs = ClientConfigService.bucket("a", "s" + i) != ClientConfigService.bucket("b", "s" + i);
		}
		assertThat(differs).isTrue();

		when(repo.activeClientConfigs()).thenReturn(List.of());
		when(repo.flags()).thenReturn(List.of(flag("flag.x", true, 30, "ALL", null, "{}")));
		String in30 = null;
		String out30 = null;
		for (int i = 0; i < 100 && (in30 == null || out30 == null); i++) {
			String s = "s-" + i;
			if (ClientConfigService.bucket("flag.x", s) < 30) {
				in30 = s;
			}
			else {
				out30 = s;
			}
		}
		assertThat(service.resolve(ClientPlatform.ANDROID, v("1.0.0"), in30).flags()).containsEntry("flag.x", true);
		assertThat(service.resolve(ClientPlatform.ANDROID, v("1.0.0"), out30).flags()).containsEntry("flag.x", false);
	}

	@Test
	void snapshotIsCachedForTheTtlThenReloaded() {
		when(repo.flags()).thenReturn(List.of());
		when(repo.activeClientConfigs()).thenReturn(List.of(cfg("minSupportedAppVersion", "ALL", 1, "\"1.0.0\"")));
		for (int i = 0; i < 5; i++) {
			service.minSupported(ClientPlatform.ANDROID);
			service.resolve(ClientPlatform.ANDROID, null, null);
		}
		verify(repo, times(1)).activeClientConfigs();
		verify(repo, times(1)).flags();

		when(repo.activeClientConfigs()).thenReturn(List.of(cfg("minSupportedAppVersion", "ALL", 2, "\"3.0.0\"")));
		clock.now = clock.now.plusSeconds(29);
		assertThat(service.minSupported(null)).isEqualTo(v("1.0.0"));
		clock.now = clock.now.plusSeconds(2);
		assertThat(service.minSupported(null)).isEqualTo(v("3.0.0"));
		verify(repo, times(2)).activeClientConfigs();
	}

	@Test
	void failedReloadKeepsServingThePreviousSnapshotAndRetriesLater() {
		when(repo.flags()).thenReturn(List.of());
		when(repo.activeClientConfigs()).thenReturn(List.of(cfg("minSupportedAppVersion", "ALL", 1, "\"1.0.0\"")));
		service.minSupported(null);

		when(repo.activeClientConfigs()).thenThrow(new IllegalStateException("db down"));
		clock.now = clock.now.plusSeconds(31);
		assertThat(service.minSupported(null)).isEqualTo(v("1.0.0"));
		assertThat(service.minSupported(null)).isEqualTo(v("1.0.0"));
		verify(repo, times(2)).activeClientConfigs(); // initial + one failed attempt; no hammering

		org.mockito.Mockito.doReturn(List.of(cfg("minSupportedAppVersion", "ALL", 2, "\"2.0.0\""))).when(repo).activeClientConfigs();
		clock.now = clock.now.plusSeconds(6);
		assertThat(service.minSupported(null)).isEqualTo(v("2.0.0"));
	}

	@Test
	void firstLoadFailurePropagates() {
		when(repo.activeClientConfigs()).thenThrow(new IllegalStateException("db down"));
		assertThatThrownBy(() -> service.minSupported(null)).isInstanceOf(IllegalStateException.class);
	}
}
