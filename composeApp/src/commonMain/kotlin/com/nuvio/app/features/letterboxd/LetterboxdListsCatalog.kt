package com.nuvio.app.features.letterboxd

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.LetterboxdListCatalogTarget
import com.nuvio.app.features.letterboxd.ui.messageResource
import com.nuvio.app.features.letterboxd.ui.toMetaPreview
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/** Catalog screen pages ("View all") of a Letterboxd list row, see [LetterboxdListsFeed.catalogChunk]. */
internal object LetterboxdListsCatalog {
    suspend fun page(target: LetterboxdListCatalogTarget, skip: Int): CatalogPage {
        val chunk = try {
            LetterboxdLists.feed.catalogChunk(target.listKey, skip)
        } catch (error: LetterboxdException) {
            // The catalog screen shows the exception message.
            throw IllegalStateException(getString(Res.string.letterboxd_lists_error, getString(error.error.messageResource())))
        }
        return CatalogPage(
            items = chunk.films.mapNotNull { it.toMetaPreview(it.name ?: it.slug) },
            rawItemCount = chunk.consumed,
            nextSkip = chunk.nextSkip,
        )
    }
}
