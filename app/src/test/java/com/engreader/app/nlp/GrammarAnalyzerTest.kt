package com.engreader.app.nlp

import com.engreader.app.dict.Lexicon
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarAnalyzerTest {

    private val lexicon = FakeLexicon(
        mapOf(
            "government" to (PartOfSpeech.Noun to "政府"),
            "announce" to (PartOfSpeech.Verb to "宣布"),
            "plan" to (PartOfSpeech.Noun to "计划"),
            "criticise" to (PartOfSpeech.Verb to "批评"),
            "delay" to (PartOfSpeech.Noun to "延误"),
            "official" to (PartOfSpeech.Noun to "官员"),
            "say" to (PartOfSpeech.Verb to "说"),
            "report" to (PartOfSpeech.Verb to "报告"),
            "evidence" to (PartOfSpeech.Noun to "证据"),
            "suggest" to (PartOfSpeech.Verb to "表明"),
            "study" to (PartOfSpeech.Noun to "研究"),
            "reveal" to (PartOfSpeech.Verb to "揭示"),
            "decision" to (PartOfSpeech.Noun to "决定"),
            "affect" to (PartOfSpeech.Verb to "影响"),
            "thousand" to (PartOfSpeech.Noun to "千"),
            "people" to (PartOfSpeech.Noun to "人们"),
            "rise" to (PartOfSpeech.Verb to "上升"),
            "cost" to (PartOfSpeech.Noun to "成本"),
            "become" to (PartOfSpeech.Verb to "变得"),
            "unaffordable" to (PartOfSpeech.Adjective to "难以负担的"),
            "researcher" to (PartOfSpeech.Noun to "研究者"),
            "publish" to (PartOfSpeech.Verb to "发表"),
            "finding" to (PartOfSpeech.Noun to "发现"),
            "explain" to (PartOfSpeech.Verb to "解释"),
            "minister" to (PartOfSpeech.Noun to "部长"),
            "resign" to (PartOfSpeech.Verb to "辞职"),
            "arrive" to (PartOfSpeech.Verb to "到达"),
            "country" to (PartOfSpeech.Noun to "国家"),
            "week" to (PartOfSpeech.Noun to "周"),
            "expect" to (PartOfSpeech.Verb to "期待"),
            "work" to (PartOfSpeech.Verb to "起作用"),
            "said" to (PartOfSpeech.Verb to "说"),
            "problem" to (PartOfSpeech.Noun to "问题"),
        )
    )
    private val analyzer = GrammarAnalyzer(lexicon)

    @Test
    fun `a simple sentence reports one clause and a subject-verb backbone`() {
        val analysis = analyzer.analyze("The government announced the plan.")
        assertEquals(1, analysis.clauseCount)
        assertEquals("The government announced the plan", analysis.backbone.trimEnd('.'))
        assertFalse(analysis.isLong)
    }

    @Test
    fun `a verb whose stem ends in ing is still a finite verb`() {
        // `bring`, `sing`, `spring` and the rest of the family are not participles: the
        // three letters are part of the stem. They were ruled out of being finite verbs
        // *and* counted as participle markers, so a sentence opening with one had no
        // verb anywhere and its clause went unsegmented.
        val analysis = analyzer.analyze("Bring the report to the minister.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertEquals("Bring", main.verb.trim())
    }

    @Test
    fun `a because-clause is separated from the main clause`() {
        val analysis = analyzer.analyze(
            "The minister resigned because officials criticised the delay.",
        )
        assertEquals(2, analysis.clauseCount)
        val kinds = analysis.clauses.map { it.kind }
        assertTrue(kinds.contains(ClauseKind.Main))
        assertTrue(kinds.contains(ClauseKind.Adverbial))
        val adverbial = analysis.clauses.first { it.kind == ClauseKind.Adverbial }
        assertEquals("because", adverbial.marker)
    }

    @Test
    fun `a relative clause is recognised after a noun`() {
        val analysis = analyzer.analyze(
            "The report, which was published on Monday, revealed the evidence.",
        )
        assertTrue(analysis.clauses.any { it.kind == ClauseKind.Relative })
    }

    @Test
    fun `a fronted subordinate clause leaves the main clause as the backbone`() {
        val analysis = analyzer.analyze(
            "Although the study was small, it suggested a clear effect.",
        )
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertTrue(main.text.contains("suggested"))
    }

    @Test
    fun `an infinitive of purpose is flagged as non-finite`() {
        val analysis = analyzer.analyze(
            "The researchers published the findings to explain the pattern.",
        )
        assertTrue(analysis.clauses.any { it.kind == ClauseKind.NonFinite })
    }

    @Test
    fun `two coordinated clauses are both reported`() {
        val analysis = analyzer.analyze(
            "The government announced the plan and the minister explained the decision.",
        )
        assertTrue(analysis.clauseCount >= 2)
    }

    @Test
    fun `a long multi-clause sentence is marked as long`() {
        val analysis = analyzer.analyze(
            "The report, which was published after officials had criticised the delay, " +
                "revealed that the evidence had been gathered over many months, " +
                "although the study was small and the researchers warned against drawing conclusions.",
        )
        assertTrue(analysis.isLong)
        assertTrue(analysis.clauses.size >= 3)
    }

    @Test
    fun `the main clause is always present`() {
        val analysis = analyzer.analyze(
            "The decision that the minister announced affected thousands of people.",
        )
        assertEquals(ClauseKind.Main, analysis.clauses.first().kind)
    }

    @Test
    fun `a finite verb after a comma is not read as a non-finite clause`() {
        // `published` is a participle marker by shape, so the tail of the sentence was
        // cut off as a non-finite clause and the main verb disappeared with it.
        val analysis = analyzer.analyze(
            "The study, published in 2020, revealed the evidence.",
        )
        val main = analysis.clauses.first { it.kind == ClauseKind.Main && it.verb == "revealed" }
        assertEquals("revealed", main.verb)
        assertTrue("the backbone must carry the real predicate", analysis.backbone.contains("revealed"))
    }

    @Test
    fun `a relative clause does not absorb the main verb`() {
        // "announced affected" are two predicates; merging them left the relative
        // clause holding both and the main clause with no verb at all.
        val analysis = analyzer.analyze(
            "The decision that the minister announced affected thousands of people.",
        )
        val relative = analysis.clauses.first { it.kind == ClauseKind.Relative }
        assertEquals("announced", relative.verb)
        val main = analysis.clauses.first { it.kind == ClauseKind.Main && it.verb == "affected" }
        assertEquals("affected", main.verb)
    }

    @Test
    fun `a fronted clause without a comma leaves the main subject with the main clause`() {
        // The split fell in the middle of a clause, so the main clause was reported as
        // starting at "the government" but carrying no verb of its own.
        val analysis = analyzer.analyze(
            "Because the plan was late the government announced the delay.",
        )
        val main = analysis.clauses.first { it.kind == ClauseKind.Main && it.verb.isNotBlank() }
        assertEquals("the government", main.subject)
        assertEquals("announced", main.verb)
    }

    @Test
    fun `every clause reports a non-blank text and valid offsets`() {
        val sentence = "The report, which was published on Monday, revealed that costs had risen."
        val analysis = analyzer.analyze(sentence)
        analysis.clauses.forEach { clause ->
            assertTrue(clause.text.isNotBlank())
            assertTrue(clause.start >= 0)
            assertTrue(clause.end <= sentence.length)
            assertTrue(clause.start < clause.end)
        }
    }

    @Test
    fun `notes explain the backbone and each subordinate clause`() {
        val analysis = analyzer.analyze(
            "The government announced the plan because costs had become unaffordable.",
        )
        assertTrue(analysis.notes.any { it.label == "句子主干" })
        assertTrue(analysis.notes.size >= 2)
    }

    @Test
    fun `an empty sentence produces an empty analysis rather than throwing`() {
        val analysis = analyzer.analyze("   ")
        assertEquals(0, analysis.clauseCount)
        assertTrue(analysis.clauses.isEmpty())
    }

    @Test
    fun `a colon introducing an explanation starts a second clause`() {
        val analysis = analyzer.analyze(
            "He was good-looking and gentlemanlike: he had a pleasant countenance.",
        )
        assertEquals(2, analysis.clauseCount)
        assertEquals("He was good-looking and gentlemanlike", analysis.clauses[0].text)
        assertEquals("he", analysis.clauses[1].subject.lowercase())
    }

    @Test
    fun `and between two adjectives does not split the sentence`() {
        val analysis = analyzer.analyze("The plan was cheap and effective.")
        assertEquals(1, analysis.clauseCount)
    }

    @Test
    fun `and between two clauses does split the sentence`() {
        val analysis = analyzer.analyze(
            "The government announced the plan and the minister explained the decision.",
        )
        assertEquals(2, analysis.clauseCount)
    }

    @Test
    fun `the subject does not leak across a colon`() {
        val analysis = analyzer.analyze(
            "The report was long: it contained every finding.",
        )
        val second = analysis.clauses[1]
        assertFalse(second.subject.contains("report"))
    }

    @Test
    fun `chunks label the subject and the predicate`() {
        val analysis = analyzer.analyze("The government announced the plan.")
        val roles = analysis.chunks.map { it.role }
        assertTrue(roles.contains(ChunkRole.Subject))
        assertTrue(roles.contains(ChunkRole.Predicate))
    }

    @Test
    fun `a sentence opening with a contraction still reports a subject and a verb`() {
        val analysis = analyzer.analyze("We're expecting to hear from the minister this afternoon.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertEquals("We", main.subject)
        assertEquals("are expecting", main.verb)
        assertEquals("We are expecting to hear from the minister this afternoon", analysis.backbone)
    }

    @Test
    fun `a possessive is not read as a contraction`() {
        val analysis = analyzer.analyze("The minister's decision affected thousands of people.")
        val main = analysis.clauses.first()
        assertEquals("The minister's decision", main.subject)
        assertEquals("affected", main.verb)
    }

    @Test
    fun `a negated contraction is a finite verb`() {
        val analysis = analyzer.analyze("The plan won't work.")
        val main = analysis.clauses.first()
        assertEquals("The plan", main.subject)
        assertEquals("won't work", main.verb)
    }

    @Test
    fun `chunks separate the hidden subject of a leading contraction from the predicate`() {
        val analysis = analyzer.analyze("We're expecting to hear from the minister this afternoon.")
        val subject = analysis.chunks.firstOrNull { it.role == ChunkRole.Subject }
        assertEquals("We're", subject?.text)
        val predicate = analysis.chunks.firstOrNull { it.role == ChunkRole.Predicate }
        assertEquals("expecting", predicate?.text)
    }

    @Test
    fun `a prepositional phrase after the verb is adverbial, not the object`() {
        val analysis = analyzer.analyze("The government announced the plan in the morning.")
        val roles = analysis.chunks.map { it.text to it.role }
        assertTrue(roles.any { it.first == "in the morning" && it.second == ChunkRole.Adverbial })
    }

    @Test
    fun `a negated contraction keeps the subject hidden inside the word`() {
        val analysis = analyzer.analyze("It's not a huge impediment for all of us.")
        val main = analysis.clauses.first()
        assertEquals("It", main.subject)
        assertEquals("is not", main.verb)
    }

    @Test
    fun `a contraction mid-sentence does not invent a subject`() {
        val analysis = analyzer.analyze("The minister said it's a problem.")
        val main = analysis.clauses.first()
        assertEquals("The minister", main.subject)
        assertEquals("said", main.verb)
    }
}

class QuizBuilderTest {

    private val lexicon = FakeLexicon(
        mapOf(
            "matt" to (PartOfSpeech.Adjective to "表面暗淡的"),
            "barrage" to (PartOfSpeech.Noun to "连续猛击"),
            "accusation" to (PartOfSpeech.Noun to "指控"),
            "bloated" to (PartOfSpeech.Adjective to "臃肿的"),
            "senior" to (PartOfSpeech.Adjective to "年长的"),
            "rank" to (PartOfSpeech.Noun to "等级"),
            "frontline" to (PartOfSpeech.Adjective to "前线的"),
            "staff" to (PartOfSpeech.Noun to "员工"),
            "director" to (PartOfSpeech.Noun to "主管"),
            "general" to (PartOfSpeech.Noun to "将军"),
            "face" to (PartOfSpeech.Verb to "面对"),
            "claim" to (PartOfSpeech.Noun to "索赔"),
            "figure" to (PartOfSpeech.Noun to "数字"),
            "market" to (PartOfSpeech.Noun to "市场"),
            "demand" to (PartOfSpeech.Noun to "需求"),
            "report" to (PartOfSpeech.Noun to "报告"),
            "describe" to (PartOfSpeech.Verb to "描述"),
        )
    )
    private val builder = QuizBuilder(lexicon)

    @Test
    fun `a capitalised name inside a sentence is not quizzed`() {
        val text = "Matt Brittin, the BBC's director general, has faced a barrage of accusations " +
            "that frontline staff are being laid off instead of those from the bloated senior ranks. " +
            "The report described the accusations and the staff."
        val questions = builder.build(text, maxQuestions = 5)
        // The name may appear in the quoted sentence as context; it must never be the
        // word under test.
        questions.forEach { q ->
            val answer = q.options.getOrNull(q.answerIndex).orEmpty()
            assertFalse("the name Matt must not be the answer: $answer", answer.equals("Matt", true))
        }
    }

    @Test
    fun `cloze options contain the word the article actually used`() {
        val text = "The report described a barrage of accusations against senior staff. " +
            "The staff faced a barrage of accusations about the market demand. " +
            "The market demand for the figure was described in the report."
        val questions = builder.build(text, maxQuestions = 5)
        val cloze = questions.filter { it.question.contains("______") }
        assertTrue("expected at least one cloze question", cloze.isNotEmpty())
        cloze.forEach { q ->
            assertEquals(4, q.options.size)
            assertEquals(q.options.size, q.options.distinct().size)
            assertTrue(q.answerIndex in q.options.indices)
        }
    }

    @Test
    fun `cloze blanks a word that is genuinely in the sentence`() {
        val text = "The report described a barrage of accusations against senior staff. " +
            "The staff faced a barrage of accusations about the market demand."
        val questions = builder.build(text, maxQuestions = 3)
        val cloze = questions.firstOrNull { it.question.contains("______") }
        if (cloze != null) {
            val answer = cloze.options[cloze.answerIndex]
            assertTrue(
                "the answer must be the word the article used: $answer",
                text.contains(answer, ignoreCase = true),
            )
            assertFalse(cloze.question.contains(answer))
        }
    }

    @Test
    fun `every question has a valid answer index and no repeated options`() {
        val text = "The report described a barrage of accusations against senior staff, " +
            "which the director general denied. The staff faced a barrage of claims."
        val questions = builder.build(text, maxQuestions = 5)
        questions.forEach { q ->
            assertTrue(q.options.size >= 2)
            assertTrue(q.answerIndex in q.options.indices)
            assertEquals(q.options.size, q.options.distinct().size)
            assertTrue(q.explanation.isNotBlank())
        }
    }

    @Test
    fun `the blank keeps a space before the following word`() {
        val text = "The report described a barrage of accusations against senior staff. " +
            "The staff faced a barrage of accusations about the market demand. " +
            "The market demand for the figure was described in the report."
        val cloze = builder.build(text, maxQuestions = 5).firstOrNull { it.question.contains("______") }
        if (cloze != null) {
            assertTrue(
                "blank must not run into the next word: ${cloze.question}",
                !Regex("_{2,}[A-Za-z]").containsMatchIn(cloze.question),
            )
        }
    }

    @Test
    fun `options under an english blank are english words, not chinese glosses`() {
        // When the article itself supplied fewer than three same-part-of-speech words
        // the pool fell back to the *gloss* distractors, so the question offered a
        // Chinese option under an English blank — which is not a wrong answer but a
        // giveaway, since only one option is even in the right language.
        val text = "The barrage was relentless throughout the long afternoon. " +
            "Another barrage followed the first barrage, and the barrage continued."
        val cloze = builder.build(text, maxQuestions = 3).firstOrNull { it.question.contains("______") }
        assertNotNull("expected a cloze question", cloze)
        cloze!!.options.forEach { option ->
            assertTrue(
                "option must be an English word: $option",
                option.all { it.code < 0x2E80 },
            )
        }
    }

    @Test
    fun `a passage with no study-worthy vocabulary yields no questions`() {
        val questions = builder.build("The cat sat on the mat.", maxQuestions = 5)
        assertTrue(questions.isEmpty())
    }

    }

/**
 * Grammar regressions from review.
 *
 * A separate class rather than more cases in [GrammarAnalyzerTest] only because these
 * were appended after this file's last class. The lexicon is deliberately empty: every
 * verb these cases turn on (`rained`, `flooded`, `failed`, `resigned`, `announced`) is
 * read by the `-ed` suffix rule and every noun falls through `guess`, so the cases do not
 * depend on hand-specified parts of speech — which is how the original defects stayed
 * invisible. The two cases that turn on the dictionary's *shape* build their own entries.
 */
class GrammarAnalyzerReviewTest {

    private val lexicon = FakeLexicon()
    private val analyzer = GrammarAnalyzer(lexicon)

    // ------------------------------------------------------------ review regressions

    @Test
    fun `when opens a subordinate clause instead of swallowing the sentence`() {
        // `when` and `where` were missing from SUBORDINATORS while `whenever`/`wherever`
        // were in it, so `When it rained, the roads flooded.` came out as a single clause
        // whose backbone was the subordinate clause — the panel taught the opposite of
        // the truth for one of the commonest clause types in English.
        val analysis = analyzer.analyze("When it rained, the roads flooded.")
        assertTrue(
            "expected the subordinate clause to be segmented out, got ${analysis.clauses.size}",
            analysis.clauses.size >= 2,
        )
        assertTrue(
            "the fronted clause must not be the sentence's backbone",
            analysis.backbone.trim() != "When it rained",
        )
        assertTrue(
            "expected the main clause's subject, got ${analysis.clauses.map { it.subject }}",
            analysis.clauses.any { it.subject.trim() == "the roads" },
        )
    }

    @Test
    fun `where opens a subordinate clause too`() {
        val analysis = analyzer.analyze("Where the plan failed, the minister resigned.")
        assertTrue(analysis.clauses.size >= 2)
    }

    @Test
    fun `a fronted time phrase is not part of the subject`() {
        // The subject used to be "everything before the finite verb", so fronting — which
        // is ordinary in news prose — put the adverbial inside it.
        val analysis = analyzer.analyze("In 2020, the government announced the plan.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertEquals("the government", main.subject.trim())
    }

    @Test
    fun `a fronted day phrase is not part of the subject`() {
        val analysis = analyzer.analyze("On Monday, the plan failed.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertEquals("the plan", main.subject.trim())
    }

    @Test
    fun `a comma between list items does not cut the subject short`() {
        // The counter-case to the fix above: this comma separates items, it does not
        // close a clause, so the whole noun phrase is the subject.
        val analysis = analyzer.analyze("The plan, the timetable, the budget were approved.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertTrue(
            "expected the whole phrase as the subject, got '${main.subject}'",
            main.subject.contains("timetable"),
        )
    }

    @Test
    fun `a curly quote before a contraction is not the subject`() {
        // Imported EPUB prose is full of curly quotes, and a quote is not blank, so the
        // hidden-subject branch — the one that exists to read the subject out of a
        // contraction — was never reached and `“` was reported as the subject.
        val analysis = analyzer.analyze("\u201CWe're leaving,\u201D she said.")
        assertFalse(
            "a quote mark was reported as a subject",
            analysis.clauses.any {
                it.subject.isNotBlank() && it.subject.all { c -> c in QUOTE_MARKS }
            },
        )
        assertTrue(
            "expected the contraction's own subject, got ${analysis.clauses.map { it.subject }}",
            analysis.clauses.any { it.subject.trim().equals("We", ignoreCase = true) },
        )
    }

    @Test
    fun `an inflected form whose first gloss is an adjective is still a finite verb`() {
        // The bundled ECDICT files `left` under its own headword and marks the *first*
        // gloss line with the adjective reading (a. 左边的…), carrying the verb reading of
        // `leave` as the alternative reading. `posOf` answers from that first line — it is
        // the reading the lookup sheet shows — so `isFiniteVerb` rejected `left`, the
        // clause found no finite verb at all, and `She left the room.` was reported as
        // "the sentence as written": no subject, no predicate, no backbone.
        val lex = HomographLexicon(
            mapOf(
                "left" to entry(
                    lemma = "left",
                    translation = "a. 左边的, 左侧的",
                    alternative = "leave" to listOf("动词 离开，动身"),
                ),
                "room" to entry(lemma = "room", translation = "n. 房间"),
            ),
        )
        val analysis = GrammarAnalyzer(lex).analyze("She left the room.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertEquals("She", main.subject.trim())
        assertEquals("left", main.verb.trim())
    }

    @Test
    fun `a noun that is also a verb is not the predicate just for having a verb sense`() {
        // The counter-case: scanning every line of the primary gloss would make `interest`
        // a finite verb here, and `buildClause` takes the *first* one, so the predicate
        // would be `interest` and the subject empty. Only a *different* headword the
        // spelling belongs to is evidence enough.
        val lex = HomographLexicon(
            mapOf(
                "interest" to entry(lemma = "interest", translation = "n. 兴趣\nv. 使感兴趣"),
                // A plural noun that carries a verb sense on a later gloss line and has
                // no alternative headword: scanning every gloss line would call this the
                // predicate (and, being the first verb-shaped token, would leave the
                // subject empty). Only a different headword is evidence enough.
                "rates" to entry(lemma = "rates", translation = "n. 比率\nv. 把…列为"),
                // `rose` has the shape the fix is for: its own headword is a noun and only
                // its second reading is a verb.
                "rose" to entry(
                    lemma = "rose",
                    translation = "n. 玫瑰, 蔷薇",
                    alternative = "rise" to listOf("动词 上升"),
                ),
                "percent" to entry(lemma = "percent", translation = "n. 百分比"),
            ),
        )
        val analysis = GrammarAnalyzer(lex).analyze("Interest rates rose to 3.5 per cent.")
        val main = analysis.clauses.first { it.kind == ClauseKind.Main }
        assertEquals("Interest rates", main.subject.trim())
        assertEquals("rose", main.verb.trim())
    }

    private val QUOTE_MARKS = setOf('\u201C', '\u201D', '\u2018', '\u2019', '"', '\'')

    /** A lexicon whose entries carry a second reading, the shape `Dictionary` produces. */
    private class HomographLexicon(
        private val entries: Map<String, WordEntry>,
    ) : Lexicon by FakeLexicon() {
        override fun lookup(raw: String): WordEntry? = entries[Tokenizer.normalize(raw)]
    }

    private fun entry(
        lemma: String,
        translation: String,
        alternative: Pair<String, List<String>>? = null,
    ) = WordEntry(
        lemma = lemma,
        queried = lemma,
        phonetic = "",
        translation = translation,
        definition = "",
        collins = 2,
        oxford = false,
        frq = 12000,
        bnc = 12000,
        tag = "",
        otherReadings = alternative?.let { listOf(it) }.orEmpty(),
    )
}
