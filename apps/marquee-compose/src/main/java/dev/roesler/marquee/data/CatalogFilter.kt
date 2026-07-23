package dev.roesler.marquee.data

enum class CatalogFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SERIES("Series"),
    ;

    fun accepts(type: MediaType): Boolean =
        when (this) {
            ALL -> true
            MOVIES -> type == MediaType.MOVIE
            SERIES -> type == MediaType.TV
        }
}

internal fun filterCatalogRows(
    rows: List<MediaRow>,
    filter: CatalogFilter,
): List<MediaRow> {
    if (filter == CatalogFilter.ALL) return rows
    return rows.mapNotNull { row ->
        row.copy(items = row.items.filter { filter.accepts(it.type) })
            .takeIf { it.items.isNotEmpty() }
    }
}
