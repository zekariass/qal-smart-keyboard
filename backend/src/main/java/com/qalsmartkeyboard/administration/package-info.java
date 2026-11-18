/**
 * Admin console back end: operator access ({@link com.qalsmartkeyboard.administration.AdminAccess}, roles and
 * permissions from {@code admin_roles}/{@code user_admin_roles}) and the audit trail
 * ({@link com.qalsmartkeyboard.administration.AuditTrail}). Domain admin operations live in their owning modules (pack
 * publishing: {@code languages}) and use these two.
 */
@ApplicationModule(displayName = "Administration")
package com.qalsmartkeyboard.administration;

import org.springframework.modulith.ApplicationModule;
