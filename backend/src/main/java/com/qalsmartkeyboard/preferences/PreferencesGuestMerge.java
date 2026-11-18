package com.qalsmartkeyboard.preferences;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.GuestMergeParticipant;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * Guest merge (B2.6, ADR 0010) for the preference tables (SD 10, V1 section 5). Conflict rule: <b>the registered
 * account's settings win</b>; the guest's are taken only where the target has none. Rows are moved (same ids), and the
 * guest's leftovers are deleted.
 *
 * <ul>
 * <li>{@code user_preferences} (one row per user): moved if the target has none, with its {@code revision} (always at least
 * 1, so a target device that cached "no document" = revision 0 gets a {@code REVISION_CONFLICT} on its next write).</li>
 * <li>{@code user_typing_languages} (one ordered list, at most one primary): the guest's whole list is moved if the
 * target has none; lists are never interleaved. The guest's list revision row is dropped; if a list moved, the target's
 * revision is incremented (created at 1 when absent) so the target's devices re-read it.</li>
 * <li>{@code user_style_profiles} (one per language): moved per language the target has no profile for; the encrypted
 * sign-off and phrases are re-encrypted for the target.</li>
 * <li>{@code user_destination_profiles}: all moved, labels re-encrypted; a moved profile stays the default only if the
 * target has no default. Labels are not compared, so two profiles with the same name can result (the user can delete
 * one).</li>
 * <li>{@code user_app_destination_rules}: a rule for an app (package HMAC) the target already has a rule for is
 * dropped; the rest move with their profile or language. The rule key is per account (ADR 0012): when both accounts have
 * one, all the guest's rules are dropped (their hashes use another key); when only the guest has one it moves to the
 * target. The target's rules revision is bumped when rules moved.</li>
 * </ul>
 * A value that does not open as the guest (corrupt) is not moved; it is logged by table and row id and deleted with the
 * guest's leftovers (it would be unreadable after the guest's keys are shredded anyway).
 */
@Component
class PreferencesGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(PreferencesGuestMerge.class);
	static final String STYLE = "user_style_profiles";
	static final String PROFILES = "user_destination_profiles";

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	PreferencesGuestMerge(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int mergeOrder() {
		return PREFERENCES;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		Timestamp ts = Timestamp.from(now);
		int preferences = jdbc.sql("""
				UPDATE user_preferences SET user_id = :target, updated_at = :now
				WHERE user_id = :guest AND NOT EXISTS (SELECT 1 FROM user_preferences WHERE user_id = :target)
				""").param("target", target).param("guest", guest).param("now", ts).update();
		jdbc.sql("DELETE FROM user_preferences WHERE user_id = :guest").param("guest", guest).update();

		int languages = jdbc.sql("""
				UPDATE user_typing_languages SET user_id = :target, updated_at = :now
				WHERE user_id = :guest AND NOT EXISTS (SELECT 1 FROM user_typing_languages WHERE user_id = :target)
				""").param("target", target).param("guest", guest).param("now", ts).update();
		jdbc.sql("DELETE FROM user_typing_languages WHERE user_id = :guest").param("guest", guest).update();
		jdbc.sql("DELETE FROM user_typing_language_revisions WHERE user_id = :guest").param("guest", guest).update();
		if (languages > 0) {
			// the target's list changed under it: a device that cached the old revision must hit a REVISION_CONFLICT
			jdbc.sql("""
					INSERT INTO user_typing_language_revisions (user_id, revision, updated_at) VALUES (:target, 1, :now)
					ON CONFLICT (user_id) DO UPDATE SET revision = user_typing_language_revisions.revision + 1,
					    updated_at = EXCLUDED.updated_at
					""").param("target", target).param("now", ts).update();
		}

		int styles = mergeStyleProfiles(guest, target, ts);
		int profiles = mergeDestinationProfiles(guest, target, ts);
		log.info("Guest merge {} -> {}: preferences {}, typing-language rows {}, style profiles {}, destination "
				+ "profiles {}", guest, target, preferences, languages, styles, profiles);
	}

	private int mergeStyleProfiles(UUID guest, UUID target, Timestamp now) {
		record Style(UUID id, byte[] signoff, byte[] phrases) {
		}
		List<Style> rows = jdbc.sql("""
				SELECT g.id, g.signoff_ciphertext, g.preferred_phrases_ciphertext FROM user_style_profiles g
				WHERE g.user_id = :guest AND NOT EXISTS (
				    SELECT 1 FROM user_style_profiles t WHERE t.user_id = :target AND t.language_id = g.language_id)
				ORDER BY g.id
				""").param("guest", guest).param("target", target)
				.query((rs, n) -> new Style(rs.getObject("id", UUID.class), rs.getBytes("signoff_ciphertext"),
						rs.getBytes("preferred_phrases_ciphertext")))
				.list();
		int moved = 0;
		for (Style row : rows) {
			List<EncryptedValue> sealed;
			try {
				sealed = encryption.reencryptRow(guest, target,
						List.of(EncryptionContext.of(STYLE, "signoff_ciphertext", row.id()),
								EncryptionContext.of(STYLE, "preferred_phrases_ciphertext", row.id())),
						Arrays.asList(row.signoff(), row.phrases()));
			}
			catch (CiphertextRejectedException e) {
				log.warn("Guest merge: {} row {} does not decrypt and is not moved", STYLE, row.id());
				continue;
			}
			Integer keyVersion = sealed.get(0) != null ? Integer.valueOf(sealed.get(0).keyVersion())
					: sealed.get(1) != null ? Integer.valueOf(sealed.get(1).keyVersion()) : null;
			jdbc.sql("""
					UPDATE user_style_profiles SET user_id = :target, signoff_ciphertext = :signoff,
					       preferred_phrases_ciphertext = :phrases, key_version = COALESCE(:keyVersion, key_version),
					       updated_at = :now
					WHERE id = :id AND user_id = :guest
					""").param("target", target).param("signoff", ciphertext(sealed.get(0)), Types.BINARY)
					.param("phrases", ciphertext(sealed.get(1)), Types.BINARY)
					.param("keyVersion", keyVersion, Types.INTEGER).param("now", now)
					.param("id", row.id()).param("guest", guest).update();
			moved++;
		}
		jdbc.sql("DELETE FROM user_style_profiles WHERE user_id = :guest").param("guest", guest).update();
		return moved;
	}

	/**
	 * Profiles carry the rules that point at them through a composite foreign key {@code (profile id, user_id)}, so a
	 * profile's rules are taken off, the profile moves, and the rules are inserted again for the target (same ids).
	 */
	private int mergeDestinationProfiles(UUID guest, UUID target, Timestamp now) {
		mergeRuleKey(guest, target);
		// the target's rule for an app wins
		jdbc.sql("""
				DELETE FROM user_app_destination_rules g
				WHERE g.user_id = :guest AND EXISTS (
				    SELECT 1 FROM user_app_destination_rules t
				    WHERE t.user_id = :target AND t.platform = g.platform AND t.app_package_hmac = g.app_package_hmac)
				""").param("guest", guest).param("target", target).update();
		// rules that point at a language move as they are
		int movedRules = jdbc.sql("""
				UPDATE user_app_destination_rules SET user_id = :target, updated_at = :now
				WHERE user_id = :guest AND destination_profile_id IS NULL
				""").param("target", target).param("guest", guest).param("now", now).update();
		record Rule(UUID id, String platform, String hmac, UUID profileId, Timestamp createdAt) {
		}
		List<Rule> profileRules = jdbc.sql("""
				SELECT id, platform, app_package_hmac, destination_profile_id, created_at
				FROM user_app_destination_rules WHERE user_id = :guest AND destination_profile_id IS NOT NULL
				ORDER BY id
				""").param("guest", guest)
				.query((rs, n) -> new Rule(rs.getObject("id", UUID.class), rs.getString("platform"),
						rs.getString("app_package_hmac"), rs.getObject("destination_profile_id", UUID.class),
						rs.getTimestamp("created_at")))
				.list();
		jdbc.sql("DELETE FROM user_app_destination_rules WHERE user_id = :guest").param("guest", guest).update();

		record Profile(UUID id, byte[] label) {
		}
		List<Profile> profiles = jdbc.sql(
				"SELECT id, label_ciphertext FROM user_destination_profiles WHERE user_id = :guest ORDER BY sort_order, id")
				.param("guest", guest)
				.query((rs, n) -> new Profile(rs.getObject("id", UUID.class), rs.getBytes("label_ciphertext"))).list();
		List<UUID> moved = new ArrayList<>();
		for (Profile profile : profiles) {
			EncryptionContext context = EncryptionContext.of(PROFILES, "label_ciphertext", profile.id());
			EncryptedValue label;
			try {
				label = encryption.reencrypt(guest, context, profile.label(), target, context);
			}
			catch (CiphertextRejectedException e) {
				log.warn("Guest merge: {} row {} does not decrypt and is not moved", PROFILES, profile.id());
				continue;
			}
			jdbc.sql("""
					UPDATE user_destination_profiles p SET user_id = :target, label_ciphertext = :label,
					       key_version = :keyVersion, updated_at = :now,
					       is_default = p.is_default AND NOT EXISTS (
					           SELECT 1 FROM user_destination_profiles t WHERE t.user_id = :target AND t.is_default)
					WHERE p.id = :id AND p.user_id = :guest
					""").param("target", target).param("label", label.ciphertext())
					.param("keyVersion", label.keyVersion()).param("now", now).param("id", profile.id())
					.param("guest", guest).update();
			moved.add(profile.id());
		}
		for (Rule rule : profileRules) {
			if (moved.contains(rule.profileId())) {
				movedRules++;
				jdbc.sql("""
						INSERT INTO user_app_destination_rules (id, user_id, platform, app_package_hmac,
						                                        destination_profile_id, created_at, updated_at)
						VALUES (:id, :target, :platform, :hmac, :profileId, :createdAt, :now)
						""").param("id", rule.id()).param("target", target).param("platform", rule.platform())
						.param("hmac", rule.hmac()).param("profileId", rule.profileId())
						.param("createdAt", rule.createdAt()).param("now", now).update();
			}
		}
		jdbc.sql("DELETE FROM user_destination_profiles WHERE user_id = :guest").param("guest", guest).update();
		jdbc.sql("DELETE FROM user_app_rule_revisions WHERE user_id = :guest").param("guest", guest).update();
		if (movedRules > 0) {
			// the target's rules changed under it: a device that cached the old revision must hit a REVISION_CONFLICT
			AppDestinationRulesService.bump(jdbc, target);
		}
		return moved.size();
	}

	/**
	 * ADR 0012: the app-rule HMAC key is per account. If both accounts have one, the guest's rules were hashed under a
	 * key the target's devices do not use and cannot be re-hashed (the server never sees package names), so they are
	 * dropped before the move and the target's devices re-create them. If only the guest has one, it becomes the
	 * target's (re-encrypted for the target), so the moved rules keep matching. A guest key never survives the merge.
	 */
	private void mergeRuleKey(UUID guest, UUID target) {
		byte[] guestKey = jdbc.sql("SELECT key_ciphertext FROM user_app_rule_keys WHERE user_id = :guest")
				.param("guest", guest).query(byte[].class).optional().orElse(null);
		if (guestKey == null) {
			return;
		}
		boolean targetHasKey = jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_app_rule_keys WHERE user_id = :target)")
				.param("target", target).query(Boolean.class).single();
		if (targetHasKey) {
			int dropped = jdbc.sql("DELETE FROM user_app_destination_rules WHERE user_id = :guest").param("guest", guest)
					.update();
			log.info("Guest merge {} -> {}: {} app rules dropped (different rule keys)", guest, target, dropped);
		}
		else {
			try {
				EncryptedValue sealed = encryption.reencrypt(guest, AppRuleKeys.context(guest), guestKey, target,
						AppRuleKeys.context(target));
				jdbc.sql("""
						INSERT INTO user_app_rule_keys (user_id, key_ciphertext, key_version) VALUES (:target, :ct, :version)
						""").param("target", target).param("ct", sealed.ciphertext(), Types.BINARY)
						.param("version", sealed.keyVersion()).update();
			}
			catch (CiphertextRejectedException e) {
				log.warn("Guest merge: {} of {} does not decrypt; its rules are dropped", AppRuleKeys.TABLE, guest);
				jdbc.sql("DELETE FROM user_app_destination_rules WHERE user_id = :guest").param("guest", guest).update();
			}
		}
		jdbc.sql("DELETE FROM user_app_rule_keys WHERE user_id = :guest").param("guest", guest).update();
	}

	private static byte[] ciphertext(EncryptedValue value) {
		return value == null ? null : value.ciphertext();
	}
}
