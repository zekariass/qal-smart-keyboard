package com.qalsmartkeyboard.identity;

import java.util.UUID;

/**
 * Port through which the data export job (privacy module, SD 19.4, plan B6.1, ADR 0022) collects a user's data from
 * every module that owns per-user tables. The mirror image of {@link UserDeletionParticipant}: each module implements
 * it for its own tables (the job has no access to them; modules depend on identity, never the other way round).
 * Implementations are Spring beans; the job calls them in ascending {@link #exportOrder()}.
 *
 * <p>Contract for implementations:
 * <ul>
 * <li>Read only. Run inside the job's read-only, repeatable-read transaction, so all sections show one consistent
 * snapshot. Never open a transaction of your own and never write.</li>
 * <li>Decrypt the user's ciphertext with {@code UserDataEncryption} (the plaintext exists only inside the archive,
 * which is itself encrypted at rest); never put a ciphertext, key, token hash, secret or another person's data in a
 * row ({@link ExportRows} refuses binary values).</li>
 * <li>Always write every section the module owns, even with zero rows, and say with {@link ExportWriter#excluded} what
 * is left out on purpose, so the manifest accounts for every table of the module.</li>
 * <li>Log ids and counts only.</li>
 * </ul>
 * A module that adds a per-user table must extend its participant (or add one) in the same change, the same rule as
 * for deletion.
 */
public interface UserExportParticipant {

	/** Values of {@link #exportOrder()} (lower first): the order of the sections in the archive. */
	int IDENTITY = 100;
	int DEVICES = 200;
	int PREFERENCES = 300;
	int PRIVACY = 400;
	/** Personalization tables with a participant of their own (the creative backup, ADR 0050). */
	int PERSONALIZATION = 450;
	int LANGUAGES = 500;
	int GLOSSARIES = 600;
	int USAGE = 700;
	int ENTITLEMENTS = 800;
	int AI_GATEWAY = 900;
	/** Tables whose owning module has no code yet (privacy holds them until it does). */
	int UNOWNED = 1000;

	/** Position of this participant: one of the constants above. */
	int exportOrder();

	/** Writes this module's sections for {@code userId}. */
	void exportUser(UUID userId, ExportWriter out);
}
