package com.nuvio.app.features.catalog

/**
 * Nuuvio fork: "View all" of a Letterboxd list row (films only). Declared here because [CatalogTarget] is sealed;
 * pages come from `features/letterboxd` (`LetterboxdListsCatalog`), hooked with one branch in `CatalogRepository`.
 */
data class LetterboxdListCatalogTarget(val listKey: String) : CatalogTarget {
    override val contentType: String get() = "movie"
    override val supportsPagination: Boolean get() = true
}
