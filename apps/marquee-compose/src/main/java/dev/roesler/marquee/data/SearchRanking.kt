package dev.roesler.marquee.data

import java.util.Locale

/**
 * Re-ranks TMDB search pages without discarding the service's useful relevance order.
 *
 * Exact and prefix matches should not be buried by a more popular title containing the same
 * words. An optional trailing year ("Dune 2021" or "Dune (2021)") disambiguates remakes while
 * every remaining tie keeps TMDB's original order.
 */
internal fun rankTitleSearch(query: String, items: List<MediaItem>): List<MediaItem> {
    val parsed = parseTitleSearchQuery(query)
    if (parsed.normalizedTitle.isBlank()) return items.distinctBy(MediaItem::key)
    val queryTokens = parsed.normalizedTitle.split(' ').filter(String::isNotBlank).toSet()

    data class Ranked(
        val item: MediaItem,
        val matchTier: Int,
        val yearPenalty: Int,
        val sourceIndex: Int,
    )

    return items
        .distinctBy(MediaItem::key)
        .mapIndexed { index, item ->
            val title = normalizeSearchText(item.title)
            val titleTokens = title.split(' ').filter(String::isNotBlank).toSet()
            val tier = when {
                title == parsed.normalizedTitle -> 0
                title.startsWith(parsed.normalizedTitle) -> 1
                titleTokens.containsAll(queryTokens) -> 2
                titleTokens.any(queryTokens::contains) -> 3
                else -> 4
            }
            Ranked(
                item = item,
                matchTier = tier,
                yearPenalty = if (parsed.year == null || item.year == parsed.year) 0 else 1,
                sourceIndex = index,
            )
        }
        .sortedWith(
            compareBy<Ranked>(Ranked::matchTier, Ranked::yearPenalty, Ranked::sourceIndex),
        )
        .map(Ranked::item)
}

private data class ParsedTitleSearch(val normalizedTitle: String, val year: String?)

private fun parseTitleSearchQuery(query: String): ParsedTitleSearch {
    val trimmed = query.trim()
    val suffix = YEAR_SUFFIX.find(trimmed)
    val withoutYear = suffix
        ?.let { trimmed.removeRange(it.range).trim() }
        ?.takeIf(String::isNotBlank)
        ?: trimmed
    return ParsedTitleSearch(
        normalizedTitle = normalizeSearchText(withoutYear),
        year = suffix?.groupValues?.getOrNull(1),
    )
}

private fun normalizeSearchText(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC_SEARCH, " ")
        .trim()
        .replace(MULTIPLE_SEARCH_SPACES, " ")

private val YEAR_SUFFIX = Regex("""(?:\s+|\s*\()((?:19|20|21)\d{2})\)?$""")
private val NON_ALPHANUMERIC_SEARCH = Regex("""[^\p{L}\p{N}]+""")
private val MULTIPLE_SEARCH_SPACES = Regex("""\s+""")
