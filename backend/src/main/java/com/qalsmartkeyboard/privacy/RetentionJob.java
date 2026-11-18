package com.qalsmartkeyboard.privacy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.retention.RetentionRule;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The retention job (B6.3, ADR 0020): runs every executable rule of the {@link RetentionPolicy} in bounded batches.
 *
 * <ul>
 * <li><b>Batches</b>: one transaction per batch of {@code batch-size} rows; a rule continues while its batches come back
 * full, up to {@code max-batches-per-rule} per run, and picks up the rest in the next run (resumable: a batch only removes
 * rows past the cutoff, so nothing is repeated).</li>
 * <li><b>Several instances</b>: each batch first takes a transaction-scoped advisory lock named after the rule
 * ({@code pg_try_advisory_xact_lock}); an instance that cannot get it leaves that rule to the one that holds it. Within a
 * batch rows are locked {@code FOR UPDATE SKIP LOCKED}. db-scheduler already runs the task on one instance at a time; these
 * make overlapping runs (a slow run, a manual trigger, a restart) harmless.</li>
 * <li><b>Isolation of failures</b>: a rule that throws is logged (rule code and exception class only; a database message
 * can carry values) and counted, and the other rules still run. The task itself does not fail, so the next run is on
 * schedule; {@code qal.retention.failures} must alert.</li>
 * <li><b>Metrics</b> (no row content, tags are rule codes): {@code qal.retention.purged{rule,action}} rows deleted or
 * changed, {@code qal.retention.failures{rule}}, {@code qal.retention.skipped{rule}} (lock held elsewhere) and the timer
 * {@code qal.retention.run}.</li>
 * </ul>
 */
public class RetentionJob {

	private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

	/** What one run did. */
	public record Result(Map<String, Integer> purged, List<String> failed, List<String> skipped) {

		public int total() {
			return purged.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final RetentionPolicy policy;
	private final RetentionProperties properties;
	private final Clock clock;
	private final MeterRegistry meters;

	RetentionJob(JdbcClient jdbc, TransactionTemplate tx, RetentionPolicy policy, RetentionProperties properties,
			Clock clock, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.policy = policy;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
	}

	/** Runs every executable rule once (up to the per-run batch cap). Never throws for a failing rule. */
	public Result run() {
		Instant now = clock.instant();
		long started = System.nanoTime();
		Map<String, Integer> purged = new LinkedHashMap<>();
		List<String> failed = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (RetentionPolicy.Entry entry : policy.entries()) {
			RetentionRule rule = entry.rule();
			if (!rule.executable()) {
				continue;
			}
			Instant cutoff = now.minus(rule.period());
			int total = 0;
			try {
				for (int batch = 0; batch < properties.maxBatchesPerRule(); batch++) {
					Integer rows = tx.execute(status -> lockedPurge(entry, now, cutoff));
					if (rows == null || rows < 0) {
						skipped.add(rule.code());
						count("qal.retention.skipped", rule);
						break;
					}
					total += rows;
					if (rows < properties.batchSize()) {
						break;
					}
				}
			}
			catch (RuntimeException e) {
				failed.add(rule.code());
				count("qal.retention.failures", rule);
				log.error("Retention rule {} failed after {} rows: {}", rule.code(), total, e.getClass().getName());
			}
			purged.put(rule.code(), total);
			if (total > 0) {
				if (meters != null) {
					meters.counter("qal.retention.purged", "rule", rule.code(), "action", rule.action().name())
							.increment(total);
				}
				log.info("Retention: {} row(s) of {} {}", total, rule.code(),
						rule.action().name().toLowerCase(java.util.Locale.ROOT));
			}
		}
		if (meters != null) {
			meters.timer("qal.retention.run").record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
		}
		return new Result(purged, failed, skipped);
	}

	/** One batch inside the caller's transaction, or -1 when another instance holds the rule's lock. */
	private int lockedPurge(RetentionPolicy.Entry entry, Instant now, Instant cutoff) {
		RetentionRule rule = entry.rule();
		boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
				.param("name", "qal.retention." + rule.code()).query(Boolean.class).single();
		if (!locked) {
			return -1;
		}
		return entry.participant().purge(rule, now, cutoff, properties.batchSize());
	}

	private void count(String name, RetentionRule rule) {
		if (meters != null) {
			meters.counter(name, "rule", rule.code()).increment();
		}
	}
}
