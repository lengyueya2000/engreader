package com.engreader.app.nlp

import com.engreader.app.dict.DifficultyBand
import com.engreader.app.dict.PartOfSpeech
import com.engreader.app.dict.WordFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozeTest {

    @Test
    fun `blanks the whole word and keeps the sentence readable`() {
        val out = Cloze.blank("Procrastination is the biggest problem.", "Procrastination")
        assertEquals("______ is the biggest problem.", out)
    }

    @Test
    fun `does not blank a longer word that merely starts with the target`() {
        // `reform` inside `reforms` was cut in the middle by the old indexOf approach,
        // leaving a dangling `s` after the blank.
        val out = Cloze.blank("The reforms stalled.", "reform")
        assertEquals("The reforms stalled.", out)
    }

    @Test
    fun `absorbs the word's own trailing punctuation`() {
        val out = Cloze.blank("The plan failed, and nobody noticed.", "failed")
        assertEquals("The plan ______ and nobody noticed.", out)
    }

    @Test
    fun `keeps a space before the following word`() {
        val out = Cloze.blank("They cancelled it.", "cancelled")
        assertEquals("They ______ it.", out)
        assertFalse("blank must not run into the next word", Regex("_{2,}[A-Za-z]").containsMatchIn(out))
    }

    @Test
    fun `matches case-insensitively`() {
        assertEquals("______ train is late.", Cloze.blank("The train is late.", "the"))
        assertEquals("______ train is late.", Cloze.blank("the train is late.", "The"))
        // The sentence-final period goes with the word, so the blank stands alone.
        assertEquals("The train is ______", Cloze.blank("The train is LATE.", "late"))
    }

    @Test
    fun `a word absent from the sentence leaves it unchanged`() {
        val sentence = "Nothing to see here."
        assertEquals(sentence, Cloze.blank(sentence, "derived"))
    }

    @Test
    fun `an inflected form in the sentence is found by its own spelling`() {
        // Review cards store the surface form, because the dictionary resolves
        // `derived` to `derive` and blanking the lemma would find nothing.
        val out = Cloze.blank("The idea was derived from earlier work.", "derived")
        assertEquals("The idea was ______ from earlier work.", out)
    }

    @Test
    fun `occurrence selects which instance to blank`() {
        val sentence = "The plan was late, and the plan was wrong."
        assertEquals("______ plan was late, and the plan was wrong.", Cloze.blank(sentence, "The", 0))
        assertEquals("The plan was late, and the ______ was wrong.", Cloze.blank(sentence, "plan", 1))
    }

    @Test
    fun `blankAt blanks exact offsets`() {
        val sentence = "The rate rose to 3.5 per cent."
        val start = sentence.indexOf("3.5")
        assertEquals("The rate rose to ______ per cent.", Cloze.blankAt(sentence, start, start + 3))
    }

    @Test
    fun `a possessive is not left dangling`() {
        // The apostrophe is part of the token, so the blank covers it and the
        // possessive does not survive as a stray `'s`.
        val out = Cloze.blank("The minister's decision was final.", "minister's")
        assertEquals("The ______ decision was final.", out)
    }

    @Test
    fun `out-of-range offsets return the sentence untouched`() {
        val sentence = "Short."
        assertEquals(sentence, Cloze.blankAt(sentence, 3, 99))
        assertEquals(sentence, Cloze.blankAt(sentence, 2, 2))
        assertEquals(sentence, Cloze.blankAt(sentence, -1, 4))
    }

    @Test
    fun `a quoted word does not leave its opening quote dangling`() {
        // Absorbing the closing `”` of `He said “hello” loudly` left the opener with
        // nothing to close, and the blank read as `He said “______ loudly`.
        assertEquals("He said “______” loudly.", Cloze.blank("He said “hello” loudly.", "hello"))
    }

    @Test
    fun `a separator is not inserted before punctuation`() {
        // The old rule added a space before any non-whitespace tail, so the closing
        // quote was pushed off the blank: `“______ ”`.
        val out = Cloze.blank("He said “hello” loudly.", "hello")
        assertFalse("no gap before the closing quote", out.contains("______ "))
    }
}

class WordFamilyTest {

    @Test
    fun `parses the exchange column in a stable order`() {
        val forms = WordFamily.parse("d:derived/3:derives/i:deriving/p:derived")
        assertEquals(listOf("derived", "deriving", "derives"), forms.map { it.word })
    }

    @Test
    fun `a spelling filed under two codes is reported once`() {
        // ECDICT stores `derived` as both the past tense and the past participle.
        val forms = WordFamily.parse("p:derived/d:derived")
        assertEquals(1, forms.size)
        assertEquals("过去式", forms.single().note)
    }

    @Test
    fun `lemma markers are not treated as forms`() {
        // `0:` names the lemma a form belongs to and `1:` names its forms; neither
        // describes this word, so neither belongs in the panel.
        assertEquals(emptyList<String>(), WordFamily.parse("0:derive").map { it.word })
        assertEquals(emptyList<String>(), WordFamily.parse("1:derives/1:deriving").map { it.word })
    }

    @Test
    fun `an empty or malformed blob yields nothing`() {
        assertTrue(WordFamily.parse("").isEmpty())
        assertTrue(WordFamily.parse("garbage").isEmpty())
        assertTrue(WordFamily.parse("p:/i:").isEmpty())
    }

    @Test
    fun `stem strips the derivational suffix`() {
        assertEquals("deriv", WordFamily.stemOf("derive"))
        assertEquals("procrastin", WordFamily.stemOf("procrastination"))
        assertEquals("govern", WordFamily.stemOf("government"))
    }

    @Test
    fun `short words have no stem, so only the exchange column is used`() {
        assertNull(WordFamily.stemOf("plan"))
        assertNull(WordFamily.stemOf("cost"))
    }

    @Test
    fun `a stem is never a different word with the ending cut off it`() {
        // `number` minus `er` is `numb`, so a prefix search for `number` returned the
        // family of `numb`: numbed, numbly, numbness. The same held for `corner`
        // (corn), `bother` (both) and `matter` (matt).
        assertEquals("number", WordFamily.stemOf("number"))
        assertEquals("corner", WordFamily.stemOf("corner"))
        assertEquals("bother", WordFamily.stemOf("bother"))
        assertEquals("matter", WordFamily.stemOf("matter"))
        // A stem search still finds a word genuinely built on the stem: `govern` is its
        // own stem, and `governor` matches on `or` from the accept list.
        assertEquals("govern", WordFamily.stemOf("govern"))
    }

    @Test
    fun `a relative built on the stem is still found`() {
        val lexicon = FakeLexicon(
            entries = mapOf(
                "govern" to (PartOfSpeech.Verb to "统治"),
                "governor" to (PartOfSpeech.Noun to "总督"),
                "governance" to (PartOfSpeech.Noun to "治理"),
            ),
            family = mapOf("govern" to listOf("govern", "governor", "governance")),
        )
        val words = WordFamily.related(lexicon, lexicon.lookup("govern")!!).map { it.word }
        assertTrue(words.contains("governor"))
        assertTrue(words.contains("governance"))
    }

    @Test
    fun `relatives never include a multi-word entry`() {
        val lexicon = FakeLexicon(
            entries = mapOf(
                "number" to (PartOfSpeech.Noun to "数字"),
                "numbered" to (PartOfSpeech.Verb to "编号"),
                "numbering" to (PartOfSpeech.Verb to "编号"),
                "number one" to (PartOfSpeech.Noun to "第一"),
                "number plate" to (PartOfSpeech.Noun to "车牌"),
            ),
            family = mapOf(
                "number" to listOf("number", "numbered", "numbering", "number one", "number plate"),
            ),
        )
        val entry = lexicon.lookup("number")!!
        val words = WordFamily.related(lexicon, entry).map { it.word }
        assertFalse(words.contains("number one"))
        assertFalse(words.contains("number plate"))
        assertTrue(words.contains("numbered"))
    }

    @Test
    fun `related combines inflections with stem matches`() {
        val lexicon = FakeLexicon(
            entries = mapOf(
                "derive" to (PartOfSpeech.Verb to "取得"),
                "derived" to (PartOfSpeech.Verb to "衍生的"),
                "derivation" to (PartOfSpeech.Noun to "起源"),
                "derivative" to (PartOfSpeech.Noun to "衍生物"),
                "derivational" to (PartOfSpeech.Adjective to "派生的"),
            ),
            family = mapOf(
                "deriv" to listOf("derive", "derived", "derivation", "derivative", "derivational"),
            ),
        )
        val entry = lexicon.lookup("derive")!!.copy(exchange = "d:derived/i:deriving")
        val related = WordFamily.related(lexicon, entry)
        val words = related.map { it.word }

        assertTrue("own inflections come first", words.indexOf("derived") < words.indexOf("derivation"))
        assertTrue(words.contains("derivation"))
        assertFalse("the entry itself is not a relative of itself", words.contains("derive"))
    }

    @Test
    fun `a candidate the dictionary cannot gloss is not offered`() {
        val lexicon = FakeLexicon(
            entries = mapOf("derive" to (PartOfSpeech.Verb to "取得")),
            family = mapOf("deriv" to listOf("derive", "derivational")),
        )
        val entry = lexicon.lookup("derive")!!.copy(exchange = "")
        // `derivational` is in the family index but has no entry, so the panel must
        // not offer a link that would open an empty sheet.
        assertTrue(WordFamily.related(lexicon, entry).isEmpty())
    }

    @Test
    fun `a coincidental prefix match is rejected`() {
        val lexicon = FakeLexicon(
            entries = mapOf(
                "govern" to (PartOfSpeech.Verb to "统治"),
                "governess" to (PartOfSpeech.Noun to "女家庭教师"),
                "governance" to (PartOfSpeech.Noun to "治理"),
            ),
            family = mapOf("govern" to listOf("govern", "governess", "governance")),
        )
        val entry = lexicon.lookup("govern")!!.copy(exchange = "")
        val words = WordFamily.related(lexicon, entry).map { it.word }
        assertTrue(words.contains("governance"))
        assertFalse("governess is a different word to a learner", words.contains("governess"))
    }
}

class VocabularyProfileTest {

    private val lexicon = FakeLexicon(
        mapOf(
            "government" to (PartOfSpeech.Noun to "政府"),
            "announce" to (PartOfSpeech.Verb to "宣布"),
            "procrastination" to (PartOfSpeech.Noun to "拖延"),
            "impediment" to (PartOfSpeech.Noun to "障碍"),
            "alleviate" to (PartOfSpeech.Verb to "缓解"),
        )
    )
    private val profile = VocabularyProfile(lexicon)

    @Test
    fun `content lemmas are distinct and exclude function words`() {
        // `announce` rather than `announced`: FakeLexicon has no lemma table, so an
        // inflected form would not resolve and the test would be about the fixture
        // rather than about the profile.
        val lemmas = profile.contentLemmas(
            "The government announce the impediment, and the government announce it again."
        )
        assertEquals(listOf("government", "announce", "impediment"), lemmas)
    }

    @Test
    fun `short and ungraded tokens are skipped`() {
        // `plan` is below the length floor and `Zack` is not in the dictionary at all.
        val lemmas = profile.contentLemmas("Zack had a plan.")
        assertTrue(lemmas.isEmpty())
    }

    @Test
    fun `a word in the stop list is skipped even when the dictionary has it`() {
        // `government` is a real entry, but function words are what make a text
        // readable, so counting them would fill the profile with words nobody needs
        // to learn.
        val withStopWord = FakeLexicon(
            mapOf(
                "that" to (PartOfSpeech.Conjunction to "那个"),
                "government" to (PartOfSpeech.Noun to "政府"),
            )
        )
        assertEquals(listOf("government"), VocabularyProfile(withStopWord).contentLemmas("that government"))
    }

    @Test
    fun `a capitalised name mid-sentence is not counted as vocabulary`() {
        // `Rose` and `March` are dictionary words as well as names; counting them
        // would put a flower and a month in the pre-study list.
        val names = FakeLexicon(
            mapOf(
                "rose" to (PartOfSpeech.Noun to "玫瑰"),
                "march" to (PartOfSpeech.Noun to "三月"),
                "government" to (PartOfSpeech.Noun to "政府"),
            )
        )
        val profile = VocabularyProfile(names)
        assertEquals(
            listOf("government"),
            profile.contentLemmas("The government met Rose in March."),
        )
    }

    @Test
    fun `a sentence-initial capital is read as ordinary vocabulary`() {
        // At the start of a sentence the capital is orthography, not a name signal.
        val profile = VocabularyProfile(
            FakeLexicon(mapOf("government" to (PartOfSpeech.Noun to "政府")))
        )
        assertEquals(listOf("government"), profile.contentLemmas("Government is difficult."))
    }

    @Test
    fun `a capital after a colon counts as sentence-initial`() {
        val profile = VocabularyProfile(
            FakeLexicon(mapOf("government" to (PartOfSpeech.Noun to "政府")))
        )
        assertEquals(listOf("government"), profile.contentLemmas("He said one thing: Government matters."))
    }

    @Test
    fun `assess reports the unknown share`() {
        val result = profile.assess(
            listOf("government", "announce", "procrastination", "impediment"),
            known = setOf("government", "announce"),
        )
        assertEquals(4, result.total)
        assertEquals(listOf("procrastination", "impediment"), result.unknown)
        assertEquals(0.5f, result.rate, 0.001f)
    }

    @Test
    fun `assess is case-insensitive about the known set`() {
        val result = profile.assess(listOf("Government"), known = setOf("government"))
        assertTrue(result.unknown.isEmpty())
    }

    @Test
    fun `an empty list is not a division by zero`() {
        val result = profile.assess(emptyList(), known = emptySet())
        assertEquals(0, result.total)
        assertEquals(0f, result.rate, 0.0001f)
    }

    @Test
    fun `the verdict follows the extensive-reading thresholds`() {
        assertEquals(VocabularyProfile.Verdict.Comfortable, profile.verdict(0.01f, 1))
        assertEquals(VocabularyProfile.Verdict.Suitable, profile.verdict(0.04f, 4))
        assertEquals(VocabularyProfile.Verdict.Stretch, profile.verdict(0.08f, 8))
        assertEquals(VocabularyProfile.Verdict.TooHard, profile.verdict(0.20f, 20))
    }

    @Test
    fun `a passage with no unknown words is comfortable even at a tiny count`() {
        assertEquals(VocabularyProfile.Verdict.Comfortable, profile.verdict(0f, 0))
    }

    @Test
    fun `encode and decode round-trip`() {
        val lemmas = listOf("procrastination", "impediment", "first-ever", "don't")
        assertEquals(lemmas, VocabularyProfile.decode(VocabularyProfile.encode(lemmas)))
        assertTrue(VocabularyProfile.decode("").isEmpty())
    }

    @Test
    fun `new words are ordered hardest first`() {
        val result = profile.assess(
            listOf("government", "procrastination", "impediment", "announce"),
            known = emptySet(),
        )
        val ordered = result.asNewWords(lexicon).map { it.lemma }
        // All five fixtures share a band through FakeLexicon, so the tie-break is
        // length; the point is that the list is ordered and complete, not arbitrary.
        assertEquals(result.unknown.size, ordered.size)
        assertEquals(ordered.sortedByDescending { it.length }, ordered)
    }

    @Test
    fun `new words carry a gloss and a band`() {
        val result = profile.assess(listOf("procrastination"), known = emptySet())
        val word = result.asNewWords(lexicon).single()
        assertEquals("procrastination", word.lemma)
        assertEquals("拖延", word.gloss)
        assertEquals(DifficultyBand.B2, word.band)
    }

    @Test
    fun `an unresolvable lemma still produces a row`() {
        val result = profile.assess(listOf("quixotic"), known = emptySet())
        val word = result.asNewWords(lexicon).single()
        assertEquals("quixotic", word.lemma)
        assertEquals(DifficultyBand.Unknown, word.band)
        assertNotNull(word)
    }
}
