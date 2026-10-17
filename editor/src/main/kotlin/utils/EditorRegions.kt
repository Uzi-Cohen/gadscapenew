package utils

import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import cache.loaders.LocationsLoader
import cache.loaders.RegionLoader
import com.google.inject.Inject
import models.RegionAccess

/** The loaders' cached definitions are the editable copies the scene is built from. */
class EditorRegions @Inject constructor(
    private val regionLoader: RegionLoader,
    private val locationsLoader: LocationsLoader
) : RegionAccess {
    override fun region(regionId: Int): RegionDefinition? = regionLoader.get(regionId)

    override fun locations(regionId: Int): LocationsDefinition? =
        try { locationsLoader.get(regionId) } catch (e: Exception) { null }
}
