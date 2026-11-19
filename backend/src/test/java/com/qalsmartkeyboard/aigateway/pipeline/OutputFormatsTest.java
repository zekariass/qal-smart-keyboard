package com.qalsmartkeyboard.aigateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR 0027: reading replies, e-mails and placeholders out of the model's plain-text answer. */
class OutputFormatsTest {

	@Test
	void repliesAreSplitOnDashLinesTrimmedUnquotedDistinctAndCapped() {
		String out = """
				"Sure, Thursday works for me."
				---
				  Could we meet at [time] instead?
				-----
				Sure, Thursday works for me.
				---
				Sorry, I can't make it this week.
				""";
		assertThat(OutputFormats.replies(out, 5)).containsExactly("Sure, Thursday works for me.",
				"Could we meet at [time] instead?", "Sorry, I can't make it this week.");
		assertThat(OutputFormats.replies(out, 2)).hasSize(2);
		assertThat(OutputFormats.replies("ሰላም\n---\nእሺ", 3)).containsExactly("ሰላም", "እሺ");
	}

	@Test
	void numberedRepliesAreReadWhenSeveralWereAskedFor() {
		assertThat(OutputFormats.replies("1. Yes, gladly.\n2) Maybe later.\n3. No, thanks.", 3))
				.containsExactly("Yes, gladly.", "Maybe later.", "No, thanks.");
		// one reply asked for: a list inside it stays one reply
		assertThat(OutputFormats.replies("Bring:\n1. bread\n2. tea", 1)).containsExactly("Bring:\n1. bread\n2. tea");
	}

	@Test
	void emptyOrBlankOutputHasNoReplies() {
		assertThat(OutputFormats.replies("", 3)).isEmpty();
		assertThat(OutputFormats.replies("---\n \n---", 3)).isEmpty();
		assertThat(OutputFormats.replies(null, 3)).isEmpty();
	}

	@Test
	void theSubjectLineIsSplitFromTheBody() {
		OutputFormats.Email e = OutputFormats.email("Subject: Sick leave tomorrow\n\nDear Ms. Johnson,\n\nI am unwell.");
		assertThat(e.subject()).isEqualTo("Sick leave tomorrow");
		assertThat(e.body()).isEqualTo("Dear Ms. Johnson,\n\nI am unwell.");
		assertThat(e.text()).isEqualTo("Sick leave tomorrow\n\nDear Ms. Johnson,\n\nI am unwell.");
		assertThat(e.toString()).doesNotContain("Johnson");
	}

	@Test
	void subjectLabelsInOtherFormsAreTolerated() {
		assertThat(OutputFormats.email("**Subject:** Meeting\nBody").subject()).isEqualTo("Meeting");
		assertThat(OutputFormats.email("SUBJECT : Meeting\n\nBody").subject()).isEqualTo("Meeting");
		assertThat(OutputFormats.email("ርዕሰ ጉዳይ፦ የስብሰባ ጥያቄ\n\nውድ አቶ ከበደ").subject()).isEqualTo("የስብሰባ ጥያቄ");
		assertThat(OutputFormats.email("Mata duree: Gaaffii\n\nKabajamoo").subject()).isEqualTo("Gaaffii");
		assertThat(OutputFormats.email("```\nSubject: Fenced\n\nBody\n```").subject()).isEqualTo("Fenced");
		assertThat(OutputFormats.email("```\nSubject: Fenced\n\nBody\n```").body()).isEqualTo("Body");
	}

	@Test
	void withoutASubjectLineEverythingIsTheBody() {
		OutputFormats.Email e = OutputFormats.email("Dear team,\n\nThe subject: budget.\n");
		assertThat(e.subject()).isNull();
		assertThat(e.body()).isEqualTo("Dear team,\n\nThe subject: budget.");
		assertThat(OutputFormats.email("Subject: only a subject").body()).isEmpty();
		assertThat(OutputFormats.email("Subject:\n\nBody").subject()).isNull();
	}

	@Test
	void aReplySubjectStartsWithOneRe() {
		assertThat(OutputFormats.replySubject("Meeting on Thursday")).isEqualTo("Re: Meeting on Thursday");
		assertThat(OutputFormats.replySubject("Re: Meeting")).isEqualTo("Re: Meeting");
		assertThat(OutputFormats.replySubject("RE: re:Meeting")).isEqualTo("Re: Meeting");
		assertThat(OutputFormats.replySubject("AW: Termin")).isEqualTo("Re: Termin");
		assertThat(OutputFormats.replySubject(null)).isNull();
		assertThat(OutputFormats.replySubject("Return policy")).isEqualTo("Re: Return policy");
	}

	@Test
	void placeholdersAreDistinctInOrderWithBrackets() {
		assertThat(OutputFormats.placeholders("Re: [project name]",
				"Dear [recipient name],\nWe meet on [date] at [time]. See [date].\n[link](https://x.org) [ቀን] []"))
				.containsExactly("[project name]", "[recipient name]", "[date]", "[time]", "[ቀን]");
		assertThat(OutputFormats.placeholders(null, "No placeholders here.")).isEmpty();
	}
}
