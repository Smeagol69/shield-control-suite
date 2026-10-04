package dev.roesler.marquee.data

/**
 * Removes avoidable repetition across generic discovery shelves.
 *
 * Semantic rows (watchlist, resume, Up Next, already-personalized retrieval) are kept byte-for-
 * byte and reserve their titles. Reorderable browse rows prefer titles not shown above them, then
 * add the smallest number of repeats needed to remain useful on TV. Row and item ordering stay
 * deterministic, so focus does not jump between refreshes.
 */
internal fun improveShelfNovelty(
    rows: List<MediaRow>,
    minimumItems: Int = DEFAULT_MINIMUM_ITEMS,
): List<MediaRow> {
    require(minimumItems > 0) { "minimumItems must be positive" }
    val seen = hashSetOf<String>()
    return rows.map { row ->
        if (!row.reorderable) {
            seen += row.items.map(MediaItem::key)
            row
        } else {
            val distinct = row.items.distinctBy(MediaItem::key)
            val novel = distinct.filterNot { it.key in seen }
            val selected = if (novel.size >= minimumItems) {
                novel
            } else {
                novel + distinct
                    .asSequence()
                    .filter { it.key in seen }
                    .take(minimumItems - novel.size)
                    .toList()
            }
            seen += selected.map(MediaItem::key)
            row.copy(items = selected)
        }
    }
}

private const val DEFAULT_MINIMUM_ITEMS = 8
