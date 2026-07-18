package com.openwave.music.features.browse

import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.data.source.DemoCatalog
import com.openwave.music.features.BrowseRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug / pre-extractor browse data from [DemoCatalog].
 * Replace with YTM InnerTube browse in Phase 2A.
 */
@Singleton
class StubBrowseRepository @Inject constructor() : BrowseRepository {

    @Volatile
    private var cache: List<BrowseShelf>? = null

    override suspend fun homeShelves(): List<BrowseShelf> {
        cache?.let { return it }
        return DemoCatalog.homeShelves().also { cache = it }
    }

    override suspend fun shelf(kind: BrowseShelfKind, params: String?): BrowseShelf {
        return homeShelves().firstOrNull { it.kind == kind }
            ?: BrowseShelf(id = kind.name, title = kind.name, kind = kind)
    }

    override fun invalidate() {
        cache = null
    }
}
