package com.qalsmartkeyboard.platform.crypto.internal;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Transaction manager for unit tests without a database. */
final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

	@Override
	protected Object doGetTransaction() {
		return new Object();
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
	}

	@Override
	protected void doCommit(DefaultTransactionStatus status) {
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) {
	}
}
