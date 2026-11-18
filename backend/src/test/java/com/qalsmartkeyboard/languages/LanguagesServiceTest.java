package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.languages.LanguageDtos.LanguageResourceDto;
import com.qalsmartkeyboard.languages.LanguagesRepository.ResourceRow;
import com.qalsmartkeyboard.languages.LanguagesService.Caller;
import com.qalsmartkeyboard.platform.ApiException;

/** Rules that need no database: INTERNAL channel switch, rollout buckets, paging over filtered rows. */
class LanguagesServiceTest {

	final LanguagesRepository repo = mock(LanguagesRepository.class);
	final FakeArtifactUrlSigner signer = new FakeArtifactUrlSigner();

	LanguagesService service(boolean internal) {
		return new LanguagesService(repo, signer, internal);
	}

	static ResourceRow row(String channel, int rollout, String min, OffsetDateTime at) {
		return new ResourceRow(UUID.randomUUID(), "am", "DICTIONARY", "1.0.0", "SHARED", channel,
				"s3://qal-language-packs/a.pack", "a".repeat(64), 10, null, null, "k", min, "MIT", "{}", null, at, rollout,
				null, null, null);
	}

	@Test
	void internalChannelNeedsAnIdentityAndTheSwitch() {
		Caller user = new Caller(UUID.randomUUID());
		assertThatThrownBy(() -> service(true).listResources(PackPlatform.ANDROID, "1.0.0", ReleaseChannel.INTERNAL, null,
				null, null, null, Caller.ANONYMOUS)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(401));
		assertThatThrownBy(() -> service(false).listResources(PackPlatform.ANDROID, "1.0.0", ReleaseChannel.INTERNAL, null,
				null, null, null, user)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
		when(repo.resources(any(), eq(List.of("INTERNAL", "BETA", "STABLE")), any(), any(), any(), any(), anyInt()))
				.thenReturn(List.of(row("INTERNAL", 100, null, OffsetDateTime.now())));
		assertThat(service(true).listResources(PackPlatform.ANDROID, "1.0.0", ReleaseChannel.INTERNAL, null, null, null, null,
				user).items()).hasSize(1);
	}

	@Test
	void internalDetailIsVisibleOnlyToAuthenticatedCallersWhenEnabled() {
		ResourceRow internal = row("INTERNAL", 100, null, OffsetDateTime.now());
		when(repo.resource(internal.id())).thenReturn(Optional.of(internal));
		assertThatThrownBy(() -> service(true).resource(internal.id(), Caller.ANONYMOUS)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service(false).resource(internal.id(), new Caller(UUID.randomUUID())))
				.isInstanceOf(ApiException.class);
		assertThat(service(true).resource(internal.id(), new Caller(UUID.randomUUID())).id()).isEqualTo(internal.id());
	}

	@Test
	void bucketIsStableInRangeAndRoughlyUniform() {
		UUID subject = UUID.randomUUID();
		UUID resource = UUID.randomUUID();
		assertThat(LanguagesService.bucket(subject, resource)).isEqualTo(LanguagesService.bucket(subject, resource));
		int below = 0;
		for (int i = 0; i < 2000; i++) {
			int b = LanguagesService.bucket(UUID.randomUUID(), resource);
			assertThat(b).isBetween(0, 99);
			if (b < 30) {
				below++;
			}
		}
		assertThat(below).isBetween(450, 750); // about 30% of 2000
	}

	@Test
	void pageFillsAcrossFilteredBatchesAndYieldsAUsableCursor() {
		List<ResourceRow> first = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			first.add(row("STABLE", 100, "9.0.0", OffsetDateTime.parse("2026-11-10T10:00:00Z").minusMinutes(i))); // all too new
		}
		List<ResourceRow> second = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			second.add(row("STABLE", 100, null, OffsetDateTime.parse("2026-11-09T10:00:00Z").minusMinutes(i)));
		}
		when(repo.resources(any(), any(), any(), any(), eq(null), eq(null), anyInt())).thenReturn(first);
		when(repo.resources(any(), any(), any(), any(), any(OffsetDateTime.class), any(UUID.class), anyInt()))
				.thenReturn(second);
		var page = service(false).listResources(PackPlatform.ANDROID, "2.0.0", null, null, null, null, 2, Caller.ANONYMOUS);
		assertThat(page.items()).extracting(LanguageResourceDto::id)
				.containsExactly(second.get(0).id(), second.get(1).id());
		assertThat(page.nextCursor()).isNotBlank();
		var last = service(false).listResources(PackPlatform.ANDROID, "2.0.0", null, null, null, null, 5, Caller.ANONYMOUS);
		assertThat(last.items()).hasSize(3);
		assertThat(last.nextCursor()).isNull();
	}
}
