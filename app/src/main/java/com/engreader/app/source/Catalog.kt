package com.engreader.app.source

import com.engreader.app.model.Source
import com.engreader.app.model.Topic

/**
 * Built-in catalog of public English news feeds.
 *
 * Every feed here is a publicly published RSS/Atom endpoint intended for
 * syndication. Nothing is scraped behind a paywall or a login: when an article
 * page cannot be fetched, the reader falls back to the feed summary so the user
 * still gets a short passage to work with.
 */
object Catalog {

    val sources: List<Source> = listOf(
        Source(
            id = "guardian-world",
            name = "The Guardian · World",
            shortName = "卫报·国际",
            feedUrl = "https://www.theguardian.com/world/rss",
            blurb = "国际新闻，长句与从句密度适中，适合精读训练",
            accent = 0xFF1F5A4A.toInt(),
            difficulty = 3,
        ),
        Source(
            id = "guardian-science",
            name = "The Guardian · Science",
            shortName = "卫报·科学",
            feedUrl = "https://www.theguardian.com/science/rss",
            blurb = "科技与考古，术语多、解释性段落长",
            accent = 0xFF1F4E79.toInt(),
            difficulty = 4,
        ),
        Source(
            id = "bbc-world",
            name = "BBC News · World",
            shortName = "BBC",
            feedUrl = "https://feeds.bbci.co.uk/news/world/rss.xml",
            blurb = "句子短、结构清晰，入门泛读首选",
            accent = 0xFF8C1D18.toInt(),
            difficulty = 2,
        ),
        Source(
            id = "nyt-world",
            name = "The New York Times · World",
            shortName = "纽约时报",
            feedUrl = "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
            blurb = "美式表达与叙事性导语，适合练语感",
            accent = 0xFF2C2C2C.toInt(),
            difficulty = 4,
        ),
        Source(
            id = "economist-week",
            name = "The Economist · The world this week",
            shortName = "经济学人",
            feedUrl = "https://www.economist.com/the-world-this-week/rss.xml",
            blurb = "高密度书面语，长难句拆解的最佳素材",
            accent = 0xFFB3261E.toInt(),
            difficulty = 5,
        ),
    )

    val topics: List<Topic> = listOf(
        Topic("world", "国际", "https://www.theguardian.com/world/rss", "地缘、外交与冲突"),
        Topic("science", "科学", "https://www.theguardian.com/science/rss", "研究、发现与考古"),
        Topic("tech", "科技", "https://www.theguardian.com/technology/rss", "产品、平台与 AI"),
        Topic("business", "商业", "https://www.theguardian.com/business/rss", "市场、公司与就业"),
        Topic("environment", "环境", "https://www.theguardian.com/environment/rss", "气候、能源与生态"),
        Topic("culture", "文化", "https://www.theguardian.com/culture/rss", "书评、影评与艺术"),
        Topic("health", "健康", "https://www.theguardian.com/society/health/rss", "医学、公共卫生"),
        Topic("education", "教育", "https://www.theguardian.com/education/rss", "学校、考试与教学"),
    )

    fun source(id: String): Source? = sources.firstOrNull { it.id == id }

    /** `sourceId` written on every chapter of an imported book. */
    const val BOOK_SOURCE = "book"

    /**
     * Display name for any feed id, whether it names a source or a topic.
     *
     * The Discover screen can load either, and both end up as `sourceId` on a stored
     * article, so a single lookup has to cover both or a topic-fetched article
     * shows no origin.
     */
    fun displayName(id: String): String =
        sources.firstOrNull { it.id == id }?.name
            ?: topics.firstOrNull { it.id == id }?.let { "${it.name} · 外刊" }
            ?: if (id == BOOK_SOURCE) "导入书籍" else ""
}
