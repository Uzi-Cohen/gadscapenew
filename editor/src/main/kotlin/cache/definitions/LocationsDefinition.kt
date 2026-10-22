package cache.definitions

import java.util.*

data class LocationsDefinition(
    val regionId: Int = 0,
    val locations: ArrayList<Location> = ArrayList<Location>(),
    /**
     * Objects on a level above 3 (build 241's underwater level), which the editor doesn't show; kept so they are
     * written back unchanged.
     */
    val hidden: ArrayList<Location> = ArrayList<Location>()
)

data class Location(
    var objId: Int = -1,
    var type: Int = 0,
    var orientation: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0
)
