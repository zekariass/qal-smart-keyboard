package com.qalsmartkeyboard.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

class AdminAccessTest {

	final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	final AuditTrail audit = mock(AuditTrail.class);
	final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), null, "REGISTERED");

	@SuppressWarnings("unchecked")
	void permissions(String... values) {
		when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of(values));
	}

	@Test
	void theInterimAdminSurfaceIsAbsentUnlessEnabled() {
		AdminAccess access = new AdminAccess(jdbc, audit, new AdminAccess.Properties(false));
		assertThatThrownBy(() -> access.require(user, AdminAccess.RESOURCES_UPLOAD, "X", "e", null))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
		verifyNoInteractions(jdbc, audit);
	}

	@Test
	void aRoleWithThePermissionOrTheWildcardIsAllowed() {
		AdminAccess access = new AdminAccess(jdbc, audit, new AdminAccess.Properties(true));
		permissions("languages:read", "resources:upload");
		assertThat(access.require(user, AdminAccess.RESOURCES_UPLOAD, "X", "e", null).userId()).isEqualTo(user.userId());
		permissions("*");
		assertThat(access.require(user, AdminAccess.SIGNING_KEYS_MANAGE, "X", "e", null).has("anything")).isTrue();
		verify(audit, never()).recordIndependently(any());
	}

	@Test
	void missingPermissionIsAuditedAndForbidden() {
		AdminAccess access = new AdminAccess(jdbc, audit, new AdminAccess.Properties(true));
		permissions("resources:upload");
		assertThatThrownBy(() -> access.require(user, AdminAccess.RESOURCES_PUBLISH, "LANGUAGE_RESOURCE_PUBLISH",
				"language_resource", "r-1"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
		ArgumentCaptor<AuditTrail.Entry> entry = ArgumentCaptor.forClass(AuditTrail.Entry.class);
		verify(audit).recordIndependently(entry.capture());
		assertThat(entry.getValue().outcome()).isEqualTo(AuditTrail.Outcome.DENIED);
		assertThat(entry.getValue().actorUserId()).isEqualTo(user.userId());
		assertThat(entry.getValue().entityId()).isEqualTo("r-1");
		assertThat(entry.getValue().metadata().get("permission")).isEqualTo("resources:publish");
	}

	@Test
	void guestsAreNeverAdmins() {
		AdminAccess access = new AdminAccess(jdbc, audit, new AdminAccess.Properties(true));
		AuthenticatedUser guest = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), null, "GUEST");
		assertThatThrownBy(() -> access.require(guest, AdminAccess.LANGUAGES_READ, "X", "e", null))
				.isInstanceOf(ApiException.class);
		verifyNoInteractions(jdbc);
	}
}
