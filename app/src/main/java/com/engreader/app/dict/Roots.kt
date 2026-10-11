package com.engreader.app.dict

/** Which slot of a word a piece occupies. */
enum class AffixKind(val label: String) {
    Prefix("前缀"),
    Root("词根"),
    Suffix("后缀"),
}

/**
 * One morpheme from the curated table.
 *
 * [example] is a word a learner is more likely to already know than the word on the
 * card, because the point of the panel is to hang the new word on something familiar.
 */
data class Affix(
    val text: String,
    val kind: AffixKind,
    val meaning: String,
    val example: String,
)

/**
 * One piece of a word after the cut.
 *
 * [affix] is null for the letters the table could not account for — the base of a
 * prefixed word, or the residue a sound change left behind. They are shown rather than
 * dropped: a breakdown that quietly swallowed letters would look like it had explained
 * the whole word when it had not.
 */
data class RootSegment(val text: String, val affix: Affix?)

/** A word cut into morphemes. */
data class RootBreakdown(val word: String, val segments: List<RootSegment>) {
    val affixes: List<Affix> get() = segments.mapNotNull { it.affix }
}

/**
 * Splits a word into prefix, root and suffix for the look-up card.
 *
 * This is a **curated table plus longest-match**, not an etymological analysis. It
 * knows about eighty roots and forty affixes, so it explains the words built from
 * those and says nothing about the rest; and because it matches spellings rather than
 * histories, it has a false-positive class a learner should be told about — `several`
 * really does look like `sever` + `-al`. The card carries that caveat.
 *
 * The table lives here rather than in an asset for two reasons: it is small enough to
 * read in one screen, and keeping it in Kotlin makes the whole rule testable without
 * an Android context (the project has no Robolectric).
 *
 * Two deliberate exclusions keep the noise down. Inflectional endings — `-ed`, `-ing`,
 * `-s` — are left out, because the 同族词 panel already covers them and because they
 * fire on words that merely end that way (`sacred`, `during`). And the affix lists hold
 * no single letters: `happy` is not `happ` + `-y` to anyone who is reading this.
 */
object Roots {

    /**
     * Below this, every cut is a coincidence. `metal` is `met` + `-al` by spelling and
     * nothing to do with either, and five letters is where those start.
     */
    private const val MIN_WORD_LENGTH = 6

    /** A piece shorter than this is not worth a chip on the card. */
    private const val MIN_PART_LENGTH = 3

    /** `unbelievably` is `un` + `believ` + `ably`; three would be a different word. */
    private const val MAX_PER_SIDE = 2

    private val PREFIXES = listOf(
        Affix("counter", AffixKind.Prefix, "反对、对抗", "counterattack"),
        Affix("inter", AffixKind.Prefix, "在…之间", "international"),
        Affix("trans", AffixKind.Prefix, "穿过、转移", "transport"),
        Affix("under", AffixKind.Prefix, "在下、不足", "underestimate"),
        Affix("super", AffixKind.Prefix, "超过、在上", "supernatural"),
        Affix("semi", AffixKind.Prefix, "一半", "semicircle"),
        Affix("anti", AffixKind.Prefix, "反对", "antibiotic"),
        Affix("auto", AffixKind.Prefix, "自己、自动", "automatic"),
        Affix("fore", AffixKind.Prefix, "在前、预先", "forecast"),
        Affix("hyper", AffixKind.Prefix, "过度", "hyperactive"),
        Affix("micro", AffixKind.Prefix, "微小", "microscope"),
        Affix("macro", AffixKind.Prefix, "宏大", "macroeconomics"),
        Affix("multi", AffixKind.Prefix, "多", "multicultural"),
        Affix("extra", AffixKind.Prefix, "超出、额外", "extraordinary"),
        Affix("ultra", AffixKind.Prefix, "极端、超", "ultraviolet"),
        Affix("over", AffixKind.Prefix, "过度、在上", "overwork"),
        Affix("post", AffixKind.Prefix, "在后", "postwar"),
        Affix("pre", AffixKind.Prefix, "在前、预先", "preview"),
        Affix("pro", AffixKind.Prefix, "向前、支持", "progress"),
        Affix("sub", AffixKind.Prefix, "在下、次一级", "submarine"),
        Affix("dis", AffixKind.Prefix, "不、分开", "disagree"),
        Affix("mis", AffixKind.Prefix, "错误地", "misunderstand"),
        Affix("non", AffixKind.Prefix, "非", "nonsense"),
        Affix("out", AffixKind.Prefix, "向外、超过", "outnumber"),
        Affix("mid", AffixKind.Prefix, "中间", "midnight"),
        Affix("uni", AffixKind.Prefix, "单一", "uniform"),
        Affix("mono", AffixKind.Prefix, "单一", "monopoly"),
        Affix("bi", AffixKind.Prefix, "二", "bicycle"),
        Affix("tri", AffixKind.Prefix, "三", "triangle"),
        Affix("co", AffixKind.Prefix, "共同", "cooperate"),
        Affix("com", AffixKind.Prefix, "共同、一起", "combine"),
        Affix("con", AffixKind.Prefix, "共同、一起", "connect"),
        Affix("cor", AffixKind.Prefix, "共同、一起", "correspond"),
        Affix("de", AffixKind.Prefix, "向下、去除", "decrease"),
        Affix("ex", AffixKind.Prefix, "向外、前任", "export"),
        Affix("en", AffixKind.Prefix, "使…", "enlarge"),
        Affix("em", AffixKind.Prefix, "使…、进入", "empower"),
        Affix("re", AffixKind.Prefix, "再次、回", "rebuild"),
        Affix("un", AffixKind.Prefix, "不、相反", "unhappy"),
        Affix("in", AffixKind.Prefix, "不、向内", "incorrect"),
        Affix("im", AffixKind.Prefix, "不、向内", "impossible"),
        Affix("il", AffixKind.Prefix, "不", "illegal"),
        Affix("ir", AffixKind.Prefix, "不", "irregular"),
    )

    private val SUFFIXES = listOf(
        Affix("ability", AffixKind.Suffix, "…的能力", "capability"),
        Affix("ibility", AffixKind.Suffix, "…的能力", "possibility"),
        Affix("ation", AffixKind.Suffix, "名词后缀：动作或结果", "information"),
        Affix("ition", AffixKind.Suffix, "名词后缀：动作或结果", "condition"),
        Affix("ology", AffixKind.Suffix, "…学", "biology"),
        Affix("ment", AffixKind.Suffix, "名词后缀：行为或结果", "movement"),
        Affix("ness", AffixKind.Suffix, "名词后缀：性质", "darkness"),
        Affix("ance", AffixKind.Suffix, "名词后缀：状态", "importance"),
        Affix("ence", AffixKind.Suffix, "名词后缀：状态", "difference"),
        Affix("ship", AffixKind.Suffix, "名词后缀：身份或关系", "friendship"),
        Affix("hood", AffixKind.Suffix, "名词后缀：时期或身份", "childhood"),
        Affix("ward", AffixKind.Suffix, "朝…方向", "forward"),
        Affix("wise", AffixKind.Suffix, "在…方面、以…方式", "likewise"),
        Affix("some", AffixKind.Suffix, "有…倾向的", "handsome"),
        Affix("like", AffixKind.Suffix, "像…的", "childlike"),
        Affix("able", AffixKind.Suffix, "能…的", "readable"),
        Affix("ible", AffixKind.Suffix, "能…的", "visible"),
        Affix("ful", AffixKind.Suffix, "充满…的", "useful"),
        Affix("less", AffixKind.Suffix, "没有…的", "useless"),
        Affix("ous", AffixKind.Suffix, "多…的、有…性质的", "famous"),
        Affix("ious", AffixKind.Suffix, "多…的", "curious"),
        Affix("ive", AffixKind.Suffix, "有…性质的", "active"),
        Affix("ity", AffixKind.Suffix, "名词后缀：性质", "reality"),
        Affix("ism", AffixKind.Suffix, "主义、学说", "realism"),
        Affix("ist", AffixKind.Suffix, "…的人", "artist"),
        Affix("ize", AffixKind.Suffix, "使…化", "modernize"),
        Affix("ise", AffixKind.Suffix, "使…化", "modernise"),
        Affix("ify", AffixKind.Suffix, "使…", "simplify"),
        Affix("ion", AffixKind.Suffix, "名词后缀：动作或状态", "action"),
        Affix("sion", AffixKind.Suffix, "名词后缀：动作或状态", "decision"),
        Affix("tion", AffixKind.Suffix, "名词后缀：动作或状态", "nation"),
        Affix("ary", AffixKind.Suffix, "…的、…的场所", "military"),
        Affix("ory", AffixKind.Suffix, "…的、…的场所", "factory"),
        Affix("ish", AffixKind.Suffix, "有点…的", "childish"),
        Affix("ese", AffixKind.Suffix, "…国的、…语", "Japanese"),
        Affix("ian", AffixKind.Suffix, "…的人、…的", "musician"),
        Affix("eer", AffixKind.Suffix, "从事…的人", "engineer"),
        Affix("let", AffixKind.Suffix, "小的…", "booklet"),
        Affix("age", AffixKind.Suffix, "名词后缀：行为或总称", "package"),
        Affix("dom", AffixKind.Suffix, "名词后缀：领域或状态", "freedom"),
        Affix("ic", AffixKind.Suffix, "…的", "basic"),
        Affix("al", AffixKind.Suffix, "…的、名词后缀", "natural"),
        Affix("ly", AffixKind.Suffix, "副词后缀", "quickly"),
        Affix("er", AffixKind.Suffix, "…的人或物", "teacher"),
        Affix("or", AffixKind.Suffix, "…的人或物", "actor"),
        Affix("ee", AffixKind.Suffix, "受动的人", "employee"),
    )

    /**
     * The roots, each a spelling that recurs across a word family.
     *
     * A root only earns its place if knowing it unlocks several words a reader meets
     * in news prose, so the list is heavy on Latin roots of common verbs and light on
     * anything a learner would not see twice.
     */
    private val ROOTS = listOf(
        Affix("prehend", AffixKind.Root, "抓住、理解", "comprehend"),
        Affix("script", AffixKind.Root, "写", "describe"),
        Affix("struct", AffixKind.Root, "建造", "structure"),
        Affix("tempor", AffixKind.Root, "时间", "temporary"),
        Affix("graph", AffixKind.Root, "写、记录", "paragraph"),
        Affix("chron", AffixKind.Root, "时间", "chronic"),
        Affix("spect", AffixKind.Root, "看", "inspect"),
        Affix("claim", AffixKind.Root, "喊、宣称", "exclaim"),
        Affix("fract", AffixKind.Root, "打碎", "fragment"),
        Affix("gress", AffixKind.Root, "走、步", "progress"),
        Affix("tract", AffixKind.Root, "拉、拖", "attract"),
        Affix("volut", AffixKind.Root, "滚动、转变", "revolution"),
        Affix("vict", AffixKind.Root, "征服", "victory"),
        Affix("vis", AffixKind.Root, "看", "visible"),
        Affix("vid", AffixKind.Root, "看", "video"),
        Affix("viv", AffixKind.Root, "生命、活", "survive"),
        Affix("cred", AffixKind.Root, "相信", "credit"),
        Affix("dict", AffixKind.Root, "说", "predict"),
        Affix("duct", AffixKind.Root, "引导", "produce"),
        Affix("ject", AffixKind.Root, "投、掷", "project"),
        Affix("lect", AffixKind.Root, "选、读", "collect"),
        Affix("miss", AffixKind.Root, "送、派", "submit"),
        Affix("mot", AffixKind.Root, "移动", "motion"),
        Affix("mov", AffixKind.Root, "移动", "movement"),
        Affix("nat", AffixKind.Root, "出生", "nature"),
        Affix("phon", AffixKind.Root, "声音", "telephone"),
        Affix("port", AffixKind.Root, "携带、搬运", "transport"),
        Affix("pos", AffixKind.Root, "放、置", "compose"),
        Affix("rupt", AffixKind.Root, "破、断", "interrupt"),
        Affix("scrib", AffixKind.Root, "写", "describe"),
        Affix("sect", AffixKind.Root, "切、割", "section"),
        Affix("sens", AffixKind.Root, "感觉", "sense"),
        Affix("sent", AffixKind.Root, "感觉", "sentiment"),
        Affix("serv", AffixKind.Root, "服务、保持", "preserve"),
        Affix("spir", AffixKind.Root, "呼吸", "spirit"),
        Affix("tain", AffixKind.Root, "持有", "contain"),
        Affix("tact", AffixKind.Root, "接触", "contact"),
        Affix("terr", AffixKind.Root, "土地", "territory"),
        Affix("vers", AffixKind.Root, "转", "convert"),
        Affix("vert", AffixKind.Root, "转", "convert"),
        Affix("vinc", AffixKind.Root, "征服", "convince"),
        Affix("vit", AffixKind.Root, "生命", "vital"),
        Affix("voc", AffixKind.Root, "叫、声音", "vocal"),
        Affix("volv", AffixKind.Root, "滚动、转变", "involve"),
        Affix("anim", AffixKind.Root, "生命、精神", "animal"),
        Affix("aud", AffixKind.Root, "听", "audio"),
        Affix("bio", AffixKind.Root, "生命", "biology"),
        Affix("ced", AffixKind.Root, "走、让", "proceed"),
        Affix("cess", AffixKind.Root, "走、让", "process"),
        Affix("clud", AffixKind.Root, "关闭", "include"),
        Affix("clus", AffixKind.Root, "关闭", "conclusion"),
        Affix("cogn", AffixKind.Root, "知道", "recognize"),
        Affix("corp", AffixKind.Root, "身体", "corporate"),
        Affix("curs", AffixKind.Root, "跑", "current"),
        Affix("dur", AffixKind.Root, "持久、硬", "durable"),
        Affix("equ", AffixKind.Root, "相等", "equal"),
        Affix("fac", AffixKind.Root, "做、制造", "factory"),
        Affix("fact", AffixKind.Root, "做、制造", "manufacture"),
        Affix("fect", AffixKind.Root, "做、制造", "perfect"),
        Affix("fer", AffixKind.Root, "带来、拿", "transfer"),
        Affix("fin", AffixKind.Root, "结束、界限", "final"),
        Affix("flex", AffixKind.Root, "弯曲", "flexible"),
        Affix("flu", AffixKind.Root, "流", "fluid"),
        Affix("form", AffixKind.Root, "形状", "reform"),
        Affix("fort", AffixKind.Root, "强壮", "effort"),
        Affix("gen", AffixKind.Root, "产生、种类", "generate"),
        Affix("geo", AffixKind.Root, "土地", "geography"),
        Affix("grad", AffixKind.Root, "走、步、级", "graduate"),
        Affix("grat", AffixKind.Root, "感谢、喜欢", "grateful"),
        Affix("hibit", AffixKind.Root, "持有、拿", "exhibit"),
        Affix("jud", AffixKind.Root, "判断", "judge"),
        Affix("junct", AffixKind.Root, "连接", "junction"),
        Affix("lev", AffixKind.Root, "举起、轻", "elevate"),
        Affix("loc", AffixKind.Root, "地方", "local"),
        Affix("log", AffixKind.Root, "说、学问", "dialogue"),
        Affix("luc", AffixKind.Root, "光", "translucent"),
        Affix("manu", AffixKind.Root, "手", "manual"),
        Affix("mem", AffixKind.Root, "记忆", "memory"),
        Affix("min", AffixKind.Root, "小、突出", "minimum"),
        Affix("mit", AffixKind.Root, "送、派", "submit"),
        Affix("nov", AffixKind.Root, "新", "novel"),
        Affix("opt", AffixKind.Root, "选择、看", "option"),
        Affix("part", AffixKind.Root, "部分、分开", "particle"),
        Affix("pass", AffixKind.Root, "经过、忍受", "passion"),
        Affix("ped", AffixKind.Root, "脚", "pedal"),
        Affix("pel", AffixKind.Root, "推、驱", "expel"),
        Affix("pend", AffixKind.Root, "悬挂、称重、花费", "depend"),
        Affix("pens", AffixKind.Root, "悬挂、称重、花费", "expensive"),
        Affix("plic", AffixKind.Root, "折叠", "complicate"),
        Affix("prim", AffixKind.Root, "第一、最初", "primary"),
        Affix("quest", AffixKind.Root, "寻求、问", "question"),
        Affix("rect", AffixKind.Root, "直、正", "correct"),
        Affix("sci", AffixKind.Root, "知道", "science"),
        Affix("sequ", AffixKind.Root, "跟随", "sequence"),
        Affix("sist", AffixKind.Root, "站、立", "assist"),
        Affix("stat", AffixKind.Root, "站、立", "stable"),
        Affix("stit", AffixKind.Root, "站、立", "institute"),
        Affix("spec", AffixKind.Root, "看", "specific"),
        Affix("tang", AffixKind.Root, "接触", "tangible"),
        Affix("ten", AffixKind.Root, "持有、保持", "maintain"),
        Affix("term", AffixKind.Root, "界限、结束", "terminal"),
        Affix("vac", AffixKind.Root, "空", "vacant"),
        Affix("vent", AffixKind.Root, "来", "convention"),
        Affix("ver", AffixKind.Root, "真实", "verify"),
        Affix("act", AffixKind.Root, "做、驱动", "action"),
        Affix("cap", AffixKind.Root, "拿、抓、头", "capture"),
        Affix("capt", AffixKind.Root, "拿、抓", "capture"),
        Affix("dic", AffixKind.Root, "说", "predict"),
        Affix("duc", AffixKind.Root, "引导", "produce"),
        Affix("frag", AffixKind.Root, "打碎", "fragment"),
        Affix("leg", AffixKind.Root, "读、选、法", "legal"),
        Affix("man", AffixKind.Root, "手", "manual"),
        Affix("nect", AffixKind.Root, "连接", "connect"),
    )

    /** Longest first, so `inter` beats `in` and `spect` beats `spec`. */
    private val PREFIXES_BY_LENGTH = PREFIXES.sortedByDescending { it.text.length }
    private val SUFFIXES_BY_LENGTH = SUFFIXES.sortedByDescending { it.text.length }
    private val ROOTS_BY_LENGTH = ROOTS.sortedByDescending { it.text.length }

    /**
     * Cuts [word] into morphemes, or null when nothing recognisable is in it.
     *
     * [isKnownWord] is what keeps a lone prefix honest. `understand` starts with `un`,
     * but `derstand` is not a word and `un-` there means nothing; asking the dictionary
     * whether the remainder stands on its own rejects that cut and keeps the ones
     * where it does (`un` + `happy`, `under` + `stand`). The default refuses every
     * remainder, which is the conservative reading.
     */
    fun explain(word: String, isKnownWord: (String) -> Boolean = { false }): RootBreakdown? {
        val w = word.lowercase().filter { it.isLetter() }
        if (w.length < MIN_WORD_LENGTH) return null

        val prefixes = mutableListOf<Affix>()
        var start = 0
        while (prefixes.size < MAX_PER_SIDE) {
            val rest = w.length - start
            val hit = PREFIXES_BY_LENGTH.firstOrNull {
                it.text.length <= rest - MIN_PART_LENGTH && w.startsWith(it.text, start)
            } ?: break
            prefixes += hit
            start += hit.text.length
        }

        val suffixes = mutableListOf<Affix>()
        var end = w.length
        while (suffixes.size < MAX_PER_SIDE) {
            val body = end - start
            val hit = SUFFIXES_BY_LENGTH.firstOrNull {
                it.text.length <= body - MIN_PART_LENGTH && w.regionMatches(end - it.text.length, it.text, 0, it.text.length)
            } ?: break
            suffixes += hit
            end -= hit.text.length
        }

        val middle = w.substring(start, end)
        val base = baseOf(middle, isKnownWord) ?: return null

        val segments = buildList {
            prefixes.forEach { add(RootSegment(it.text, it)) }
            if (base.before.isNotEmpty()) add(RootSegment(base.before, null))
            if (base.affix != null) add(RootSegment(base.affix.text, base.affix))
            if (base.after.isNotEmpty()) add(RootSegment(base.after, null))
            // Reversed: the suffixes were peeled off the end, so the last one found is
            // the one nearest the middle.
            suffixes.reversed().forEach { add(RootSegment(it.text, it)) }
        }
        // A word that is a plain base with nothing recognised around it has not been
        // broken down at all, and the card would show a single unlabelled chip.
        if (segments.none { it.affix != null }) return null
        return RootBreakdown(w, segments)
    }

    /**
     * How the unexplained middle resolves, or null when it does not.
     *
     * A root spanning the whole middle is the best answer, because it comes with a
     * meaning to teach. Failing that, a middle the dictionary knows is a word in its
     * own right — `happy` in `unhappy` — and is left as a plain base rather than being
     * cut further. Only when neither holds is a root *inside* the middle used, with
     * the letters on either side of it kept as unexplained. An empty middle means the
     * affixes consumed the word, which is a complete answer on its own.
     */
    private fun baseOf(middle: String, isKnownWord: (String) -> Boolean): Base? {
        if (middle.isEmpty()) return Base(null, "", "")
        val whole = ROOTS_BY_LENGTH.firstOrNull { it.text == middle }
        if (whole != null) return Base(whole, "", "")
        // A middle the dictionary knows is carried whole as the unlabelled base, not
        // dropped: `happy` has to appear in `un` + `happy`.
        if (isKnownWord(middle)) return Base(null, middle, "")
        val inside = ROOTS_BY_LENGTH.firstOrNull { middle.contains(it.text) } ?: return null
        val at = middle.indexOf(inside.text)
        return Base(
            affix = inside,
            before = middle.substring(0, at),
            after = middle.substring(at + inside.text.length),
        )
    }

    private data class Base(val affix: Affix?, val before: String, val after: String)
}
