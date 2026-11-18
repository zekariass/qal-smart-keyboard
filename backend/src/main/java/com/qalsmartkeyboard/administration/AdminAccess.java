package com.qalsmartkeyboard.administration;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * Role-based access to {@code /admin/v1} (SD 18, Review D25): the caller's bearer token must belong to a registered,
 * active user holding an unexpired {@code user_admin_roles} assignment whose {@code admin_roles.permissions} contain the
 * required permission (or {@code *}, SUPER_ADMIN). Refusals are audited ({@code DENIED}) and answered 403
 * {@code FORBIDDEN}; guests are never admins.
 *
 * <p>Interim (ADR 0019): SD 9.2/18 require workforce OIDC with MFA and an IP allow-list for admins, which does not exist
 * yet; until it does, the admin surface accepts the app's access tokens and is off unless {@code qal.admin.enabled=true}
 * (when off, every admin operation is 404, as if absent).
 */
@Component
public class AdminAccess {

	/** Permission strings of the V1 {@code admin_roles} seed, plus {@code signing_keys:manage} (SUPER_ADMIN only). */
	public static final String RESOURCES_UPLOAD = "resources:upload";
	public static final String RESOURCES_EDIT = "resources:edit";
	public static final String RESOURCES_APPROVE = "resources:approve";
	public static final String RESOURCES_PUBLISH = "resources:publish";
	public static final String LANGUAGES_READ = "languages:read";
	public static final String SIGNING_KEYS_MANAGE = "signing_keys:manage";

	/** {@code qal.admin.*}: {@code enabled} switches the interim admin surface on (default off). */
	@ConfigurationProperties("qal.admin")
	public record Properties(@DefaultValue("false") boolean enabled) {
	}

	/** An authorised admin. */
	public record Actor(UUID userId, Set<String> permissions) {

		public boolean has(String permission) {
			return permissions.contains("*") || permissions.contains(permission);
		}
	}

	private final JdbcTemplate jdbc;
	private final AuditTrail audit;
	private final boolean enabled;

	AdminAccess(JdbcTemplate jdbc, AuditTrail audit, Properties properties) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.enabled = properties.enabled();
	}

	/**
	 * The caller as an admin holding {@code permission}.
	 *
	 * @param action     the action code audited if access is refused
	 * @param entityType the target's type, for the refusal row
	 * @param entityId   the target's id, or null
	 * @throws ApiException 404 when the admin surface is off, 403 {@code FORBIDDEN} without the permission
	 */
	public Actor require(AuthenticatedUser user, String permission, String action, String entityType, String entityId) {
		if (!enabled) {
			throw new ApiException(ApiErrorCode.NOT_FOUND);
		}
		if (user == null) {
			throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
		}
		Set<String> permissions = user.isGuest() ? Set.of() : permissions(user.userId());
		Actor actor = new Actor(user.userId(), permissions);
		if (!actor.has(permission)) {
			audit.recordIndependently(new AuditTrail.Entry(user.userId(), null, action, entityType, entityId,
					AuditTrail.Outcome.DENIED, Map.of("permission", permission)));
			throw new ApiException(ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, null);
		}
		return actor;
	}

	private Set<String> permissions(UUID userId) {
		List<String> rows = jdbc.queryForList("""
				SELECT DISTINCT p.permission
				FROM user_admin_roles ur
				JOIN admin_roles r ON r.id = ur.admin_role_id
				JOIN users u ON u.id = ur.user_id
				CROSS JOIN LATERAL unnest(r.permissions) AS p(permission)
				WHERE ur.user_id = ? AND (ur.expires_at IS NULL OR ur.expires_at > NOW())
				  AND u.account_type = 'REGISTERED' AND u.account_status = 'ACTIVE' AND u.deleted_at IS NULL""",
				String.class, userId);
		return Set.copyOf(new HashSet<>(rows));
	}
}
