package com.qalsmartkeyboard.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** ADR 0050: the defaults of {@code qal.creative-backup.*} and the start-up checks. */
class CreativeBackupPropertiesTest {

	static CreativeBackupProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
				.bindOrCreate("qal.creative-backup", CreativeBackupProperties.class);
	}

	@Test
	void defaults() {
		CreativeBackupProperties p = bind(Map.of());
		assertThat(p.enabled()).isTrue();
		assertThat(p.maxItemBytes()).isEqualTo(2 * 1024 * 1024);
		assertThat(p.maxItemsPerKind()).isEqualTo(100);
		assertThat(p.maxTotalBytes()).isEqualTo(32L * 1024 * 1024);
		assertThat(p.rateLimit()).isEqualTo(600);
		assertThat(p.rateLimitPeriod()).isEqualTo(Duration.ofHours(1));
		// the base64 of the largest item, plus room for the other members
		assertThat(p.maxRequestBytes()).isEqualTo(4 * ((2 * 1024 * 1024 + 2) / 3) + 4096);
		// the base64 copy in a data export of a full backup stays under the 64 MiB archive cap (ADR 0022)
		assertThat(p.maxTotalBytes() * 4 / 3).isLessThan(64L * 1024 * 1024);
	}

	@Test
	void invalidValuesStopTheStart() {
		assertThatThrownBy(() -> bind(Map.of("qal.creative-backup.max-item-bytes", "0"))).hasRootCauseInstanceOf(
				IllegalArgumentException.class);
		assertThatThrownBy(() -> bind(Map.of("qal.creative-backup.max-item-bytes", "9000000")))
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> bind(Map.of("qal.creative-backup.max-items-per-kind", "0")))
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> bind(Map.of("qal.creative-backup.max-total-bytes", "100")))
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> bind(Map.of("qal.creative-backup.rate-limit", "0")))
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> bind(Map.of("qal.creative-backup.rate-limit-period", "0s")))
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}
}
