package com.alhorrtv.app

/** A smart section (e.g. "Ramadan 2026", "Shahid", "Netflix") built from the provider's own category names. */
internal data class SectionInfo(val key: String, val label: String, val icon: String, val ids: List<String>, val count: Int)

internal object Sections {
    /** groups: every group must have at least one keyword contained in the normalized category name. */
    private class Def(val key: String, val label: String, val icon: String, val groups: List<List<String>>)

    private val ARABIC = listOf("عربي", "عرب", "arab", "سوري", "مصري", "خليجي", "لبناني", "كويتي", "سعودي", "اردني", "syria", "egypt", "gulf")
    private val FOREIGN = listOf("اجنبي", "foreign", "english", "انجليزي", "امريكي", "مترجم", "dubbed", "hollywood", "usa", "uk ")
    private val TURKISH = listOf("تركي", "turk")
    private val INDIAN = listOf("هندي", "hindi", "india", "bollywood")
    private val KOREAN = listOf("كوري", "korea")
    private val ANIME = listOf("انمي", "anime", "كرتون", "cartoon")
    private val KIDS = listOf("اطفال", "kids", "children")
    private val DOC = listOf("وثائقي", "documentary")
    private val NETFLIX = listOf("netflix", "نتفليكس", "نتفلكس")
    private val SHAHID = listOf("shahid", "shahed", "شاهد")

    private val series = listOf(
        Def("ramadan2026", "رمضان 2026", "🌙", listOf(listOf("رمضان", "ramadan", "ramadhan"), listOf("2026"))),
        Def("ramadan", "رمضان", "🌙", listOf(listOf("رمضان", "ramadan", "ramadhan"))),
        Def("arabic", "عربي", "🎭", listOf(ARABIC)),
        Def("shahid", "شاهد", "▶", listOf(SHAHID)),
        Def("netflix", "نتفليكس", "N", listOf(NETFLIX)),
        Def("turkish", "تركي", "🇹🇷", listOf(TURKISH)),
        Def("foreign", "أجنبي", "🌍", listOf(FOREIGN)),
        Def("korean", "كوري", "🇰🇷", listOf(KOREAN)),
        Def("indian", "هندي", "🇮🇳", listOf(INDIAN)),
        Def("anime", "أنمي وكرتون", "✨", listOf(ANIME)),
        Def("doc", "وثائقي", "🎞", listOf(DOC))
    )

    private val movies = listOf(
        Def("new", "جديد 2026", "🔥", listOf(listOf("2026", "جديد", "new"))),
        Def("arabic", "أفلام عربية", "🎭", listOf(ARABIC)),
        Def("foreign", "أفلام أجنبية", "🌍", listOf(FOREIGN)),
        Def("netflix", "نتفليكس", "N", listOf(NETFLIX)),
        Def("turkish", "تركي", "🇹🇷", listOf(TURKISH)),
        Def("indian", "هندي", "🇮🇳", listOf(INDIAN)),
        Def("korean", "كوري", "🇰🇷", listOf(KOREAN)),
        Def("anime", "أنمي وكرتون", "✨", listOf(ANIME)),
        Def("kids", "أطفال", "🧸", listOf(KIDS)),
        Def("doc", "وثائقي", "🎞", listOf(DOC))
    )

    private val live = listOf(
        Def("arabic", "عربية", "🎭", listOf(ARABIC)),
        Def("sports", "رياضة", "⚽", listOf(listOf("رياضه", "sport", "bein", "ssc", "كاس", "دوري"))),
        Def("news", "أخبار", "📰", listOf(listOf("اخبار", "news", "الجزيره", "العربيه"))),
        Def("movies", "أفلام", "🎬", listOf(listOf("افلام", "movie", "cinema", "سينما"))),
        Def("kids", "أطفال", "🧸", listOf(KIDS + ANIME)),
        Def("foreign", "أجنبية", "🌍", listOf(FOREIGN)),
        Def("religious", "دينية", "🕌", listOf(listOf("قران", "quran", "اسلام", "islam", "دينيه"))),
        Def("music", "موسيقى", "🎵", listOf(listOf("موسيقي", "music", "اغاني")))
    )

    fun build(kind: String, categories: List<CategoryEntity>, counts: Map<String, Int>): List<SectionInfo> {
        val defs = when (kind) { Kinds.SERIES -> series; Kinds.MOVIES -> movies; Kinds.LIVE -> live; else -> emptyList() }
        val normalized = categories.map { it to ArabicSearch.normalize(it.categoryName) }
        return defs.mapNotNull { def ->
            val ids = normalized.filter { (_, name) -> def.groups.all { group -> group.any { kw -> name.contains(kw) } } }
                .map { it.first.categoryId }
            val total = ids.sumOf { counts[it] ?: 0 }
            if (ids.isEmpty() || total == 0) null else SectionInfo(def.key, def.label, def.icon, ids, total)
        }
    }
}
