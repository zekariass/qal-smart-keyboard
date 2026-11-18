package com.qalsmartkeyboard.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** B6.1: the value mapping every export section goes through. */
class ExportRowsTest {

	@Test
	void columnsBecomeCamelCase() {
		assertThat(ExportRows.camel("app_package_hmac")).isEqualTo("appPackageHmac");
		assertThat(ExportRows.camel("ID")).isEqualTo("id");
		assertThat(ExportRows.camel("has_push_token")).isEqualTo("hasPushToken");
	}

	@Test
	void valuesBecomeJsonReady() {
		Instant t = Instant.parse("2026-11-18T12:00:00.123456Z");
		assertThat(ExportRows.normalise(Timestamp.from(t))).isEqualTo("2026-11-18T12:00:00.123456Z");
		assertThat(ExportRows.normalise(OffsetDateTime.ofInstant(t, ZoneOffset.ofHours(3)))).isEqualTo("2026-11-18T12:00:00.123456Z");
		UUID id = UUID.randomUUID();
		assertThat(ExportRows.normalise(id)).isEqualTo(id.toString());
		assertThat(ExportRows.normalise(new BigDecimal("5.000000"))).isEqualTo("5");
		assertThat(ExportRows.normalise(new BigDecimal("0.250000"))).isEqualTo("0.25");
		assertThat(ExportRows.normalise(null)).isNull();
		assertThat(ExportRows.normalise(7L)).isEqualTo(7L);
		assertThat(ExportRows.normalise(true)).isEqualTo(true);
	}
}
