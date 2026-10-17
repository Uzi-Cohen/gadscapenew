package controllers.worldRenderer.entities

class WallDecoration(
    val entity: Entity?,
    /** The second half of a decoration that sits on both sides of a diagonal wall (type 8). */
    val entity2: Entity? = null
)
