package com.qalsmartkeyboard.platform.retention;

/** What the retention policy does with a data class at the end of its period. */
public enum RetentionAction {

	/** Rows are deleted by the retention job. */
	DELETE,

	/** Rows stay, but the personal or sensitive columns are cleared by the retention job. */
	ANONYMISE,

	/** Kept: financial or legal record (or no period is defined yet). The job never touches it; documented only. */
	KEEP,

	/** Deleted by the owner's own mechanism (a sweeper, a bucket lifecycle rule, the log store); the job skips it. */
	DELETE_BY_OWNER
}
