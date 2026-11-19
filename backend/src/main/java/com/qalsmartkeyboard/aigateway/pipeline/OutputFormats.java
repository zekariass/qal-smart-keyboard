package com.qalsmartkeyboard.aigateway.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the plain-text formats the templates ask the model for (ADR 0027), so the endpoints can return structured
 * fields without asking the vendor for JSON (structured output is model-specific and a refusal or truncation inside JSON
 * is harder to detect). Pure functions; nothing is logged.
 *
 * <ul>
 * <li><b>Replies</b> (SMART_REPLY): the alternatives separated by a line holding only {@code ---}. A model that numbers
 * them instead ({@code 1. ...}, {@code 2) ...}) is read as well when more than one reply was asked for.</li>
 * <li><b>E-mail</b> (DRAFT_DOCUMENT): an optional first line {@code Subject: ...} (the label is always English; a few
 * Ethiopic and Oromo labels and Markdown bold are tolerated), then the body.</li>
 * <li><b>Placeholders</b>: bracketed items the user must fill in ({@code [date]}, {@code [ቀን]}), in order of first
 * appearance, brackets included so a client can find them in the text.</li>
 * </ul>
 */
public final class OutputFormats {

	private OutputFormats() {
	}

	/** A parsed e-mail. {@code subject} null = the output had no subject line. */
	public record Email(String subject, String body) {

		/** Subject and body as one text, for the output guardrails and history. */
		public String text() {
			return subject == null || subject.isBlank() ? body : subject + "\n\n" + body;
		}

		@Override
		public String toString() {
			return "Email[subject " + (subject == null ? 0 : subject.length()) + " chars, body " + body.length()
					+ " chars]";
		}
	}

	private static final Pattern SEPARATOR = Pattern.compile("(?m)^[ \\t]*-{3,}[ \\t]*$");
	private static final Pattern NUMBERED = Pattern.compile("(?m)^[ \\t]*\\d{1,2}[.)][ \\t]+");
	private static final Pattern LEADING_MARKER = Pattern.compile("^(?:\\d{1,2}[.)]|[-*•])[ \\t]+");
	private static final Pattern SUBJECT = Pattern.compile(
			"^[ \\t]*(?:\\*\\*|__)?[ \\t]*(?:subject|subj|ርዕሰ ጉዳይ|ርእሰ ጉዳይ|ርዕስ|ጉዳይ|ኣርእስቲ|mata[ \\t]+duree)[ \\t]*"
					+ "(?:\\*\\*|__)?[ \\t]*[:：፡፦][ \\t]*(?:\\*\\*|__)?(.*)$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern RE_PREFIX = Pattern.compile("^(?:(?:re|aw|sv)[ \\t]*:[ \\t]*)+",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern PLACEHOLDER = Pattern.compile("\\[([^\\[\\]\\r\\n]{1,60})](?!\\()");
	private static final Pattern FENCE = Pattern.compile("^```[a-zA-Z]*[ \\t]*\\R|\\R?```[ \\t]*$");

	/** At most {@code max} non-empty, distinct replies, in the model's order. */
	public static List<String> replies(String output, int max) {
		if (output == null || output.isBlank() || max < 1) {
			return List.of();
		}
		String text = unfence(output);
		String[] chunks;
		if (SEPARATOR.matcher(text).find()) {
			chunks = SEPARATOR.split(text);
		} else if (max > 1 && countMatches(NUMBERED, text) >= 2) {
			chunks = NUMBERED.split(text);
		} else {
			chunks = new String[] { text };
		}
		Set<String> out = new LinkedHashSet<>();
		for (String c : chunks) {
			String r = unquote(LEADING_MARKER.matcher(c.strip()).replaceFirst("").strip());
			if (!r.isEmpty()) {
				out.add(r);
			}
			if (out.size() == max) {
				break;
			}
		}
		return List.copyOf(out);
	}

	/** The subject line (if the first non-empty line is one) and the body after it. */
	public static Email email(String output) {
		String text = output == null ? "" : unfence(output).strip();
		int lineEnd = text.indexOf('\n');
		String first = lineEnd < 0 ? text : text.substring(0, lineEnd);
		Matcher m = SUBJECT.matcher(first.strip());
		if (!m.matches()) {
			return new Email(null, text);
		}
		String subject = m.group(1).replaceAll("(\\*\\*|__)[ \\t]*$", "").strip();
		String body = lineEnd < 0 ? "" : text.substring(lineEnd + 1).strip();
		return new Email(subject.isEmpty() ? null : subject, body);
	}

	/** {@code "Re: "} once in front of the subject (never {@code "Re: Re: "}); null stays null. */
	public static String replySubject(String subject) {
		if (subject == null || subject.isBlank()) {
			return subject;
		}
		String rest = RE_PREFIX.matcher(subject.strip()).replaceFirst("").strip();
		return rest.isEmpty() ? "Re:" : "Re: " + rest;
	}

	/** Bracketed placeholders, brackets included, distinct, in order of first appearance across the texts. */
	public static List<String> placeholders(String... texts) {
		Set<String> found = new LinkedHashSet<>();
		for (String t : texts) {
			if (t == null) {
				continue;
			}
			Matcher m = PLACEHOLDER.matcher(t);
			while (m.find()) {
				if (!m.group(1).isBlank()) {
					found.add(m.group());
				}
			}
		}
		return new ArrayList<>(found);
	}

	private static String unfence(String s) {
		String t = s.strip();
		return t.startsWith("```") ? FENCE.matcher(t).replaceAll("").strip() : t;
	}

	private static String unquote(String s) {
		if (s.length() >= 2) {
			char a = s.charAt(0);
			char b = s.charAt(s.length() - 1);
			if ((a == '"' && b == '"') || (a == '“' && b == '”') || (a == '«' && b == '»')) {
				return s.substring(1, s.length() - 1).strip();
			}
		}
		return s;
	}

	private static int countMatches(Pattern p, String s) {
		Matcher m = p.matcher(s);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}
}
