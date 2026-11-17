package com.qalsmartkeyboard.testsupport.logging;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures every log event of the application (root logger, {@code com.qalsmartkeyboard} lowered to TRACE) so tests can
 * prove that secrets never reach the logs (SD 18 logging hygiene). Use in try-with-resources.
 */
public final class CapturedLogs implements AutoCloseable {

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
	private final Logger app = (Logger) LoggerFactory.getLogger("com.qalsmartkeyboard");
	private final Level previousAppLevel;

	public CapturedLogs() {
		previousAppLevel = app.getLevel();
		app.setLevel(Level.TRACE);
		appender.start();
		root.addAppender(appender);
	}

	/** Formatted messages, MDC values, throwable class names and messages of every captured event. */
	public List<String> texts() {
		List<String> texts = new ArrayList<>();
		for (ILoggingEvent e : List.copyOf(appender.list)) {
			texts.add(e.getFormattedMessage());
			texts.addAll(e.getMDCPropertyMap().values());
			for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
				texts.add(t.getClassName() + ": " + t.getMessage());
			}
		}
		return texts;
	}

	public int size() {
		return appender.list.size();
	}

	@Override
	public void close() {
		root.detachAppender(appender);
		appender.stop();
		app.setLevel(previousAppLevel);
	}
}
