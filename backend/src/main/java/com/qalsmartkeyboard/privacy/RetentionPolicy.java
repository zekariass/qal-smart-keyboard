package com.qalsmartkeyboard.privacy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;

/**
 * The retention policy registry (B6.3, ADR 0020): one entry per data class, from every module's
 * {@link RetentionParticipant}, with the period in force (the module's default from SD 19.2 or an operator override in
 * {@code qal.retention.periods.*}), the owning module and the action (delete, anonymise, keep or deleted by the owner).
 * The table in ADR 0020 is this registry written out.
 *
 * <p>Built once at start. Duplicate codes, an override for an unknown rule and an override of a rule the job does not
 * execute (a kept financial record, a class another mechanism deletes) all stop the application: a typo must not leave a
 * period silently unchanged, and a "kept" record must not be shortened by configuration.
 */
public final class RetentionPolicy {

	/** A rule in force and the participant that executes it. */
	record Entry(RetentionRule rule, RetentionParticipant participant, int order) {
	}

	private final List<Entry> entries;
	private final Map<String, Entry> byCode;

	RetentionPolicy(List<RetentionParticipant> participants, Map<String, Duration> overrides) {
		List<Entry> all = new ArrayList<>();
		Map<String, Entry> index = new LinkedHashMap<>();
		participants.stream().sorted(Comparator.comparingInt(RetentionParticipant::order)).forEach(p -> {
			for (RetentionRule rule : p.rules()) {
				Duration override = overrides.get(rule.code());
				RetentionRule inForce = override == null ? rule : rule.withPeriod(override);
				if (override != null && !rule.executable()) {
					throw new IllegalStateException("qal.retention.periods." + rule.code() + ": the rule is "
							+ rule.action() + " and cannot be changed by configuration");
				}
				Entry entry = new Entry(inForce, p, p.order());
				if (index.putIfAbsent(rule.code(), entry) != null) {
					throw new IllegalStateException("Duplicate retention rule " + rule.code());
				}
				all.add(entry);
			}
		});
		for (String key : overrides.keySet()) {
			if (!index.containsKey(key)) {
				throw new IllegalStateException("qal.retention.periods." + key + ": unknown retention rule; known: "
						+ index.keySet());
			}
		}
		this.entries = List.copyOf(all);
		this.byCode = Map.copyOf(index);
	}

	/** Every rule in force, executable or documentary, in execution order. */
	public List<RetentionRule> rules() {
		return entries.stream().map(Entry::rule).toList();
	}

	public Optional<RetentionRule> rule(String code) {
		return Optional.ofNullable(byCode.get(code)).map(Entry::rule);
	}

	List<Entry> entries() {
		return entries;
	}
}
