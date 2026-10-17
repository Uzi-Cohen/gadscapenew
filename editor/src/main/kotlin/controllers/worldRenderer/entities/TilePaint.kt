package controllers.worldRenderer.entities

import controllers.worldRenderer.Constants
import controllers.worldRenderer.components.*
import controllers.worldRenderer.helpers.GpuIntBuffer
import controllers.worldRenderer.helpers.ModelBuffers
import controllers.worldRenderer.helpers.ModelBuffers.Companion.FLAG_SCENE_BUFFER
import models.scene.SceneTile
import utils.EventType
import utils.Observable

class TilePaint(
    swHeight: Int,
    seHeight: Int,
    neHeight: Int,
    nwHeight: Int,
    swColor: Int,
    seColor: Int,
    neColor: Int,
    nwColor: Int,
    var texture: Int,
    var rgb: Int,
    hoverComponent: HoverComponent = HoverComponent(),
    selectComponent: SelectComponent = SelectComponent(),
    clickableComponent: ClickableComponent = ClickableComponent()
): Observable<TilePaint>(),
    Renderable,
    Hoverable by hoverComponent,
    Selectable by selectComponent,
    Clickable by clickableComponent {

    init {
        hoverComponent.observable = this
        selectComponent.observable = this
        clickableComponent.onClickFunc = {
            isSelected = true
        }
    }

    fun setHeight(swHeight: Int, seHeight: Int, neHeight: Int, nwHeight: Int) {
        this.swHeight = swHeight
        this.seHeight = seHeight
        this.neHeight = neHeight
        this.nwHeight = nwHeight
        notifyObservers(EventType.SELECT)
    }

    fun setColor(swColor: Int, seColor: Int, neColor: Int, nwColor: Int) {
        this.swColor = swColor
        this.seColor = seColor
        this.neColor = neColor
        this.nwColor = nwColor
        notifyObservers(EventType.SELECT)
    }

    var swHeight: Int = swHeight
        private set

    var seHeight: Int = seHeight
        private set

    var neHeight: Int = neHeight
        private set

    var nwHeight: Int = nwHeight
        private set

    var swColor: Int = swColor
        private set
        get() {
            // 12345678 = an empty tile that isn't drawn (common on upper floors): keep it hidden,
            // otherwise hovering it turns it into a visible white tile that stays behind
            if (field == 12345678) return field
            if (isSelected) {
                return Constants.SELECTED_HSL
            }
            val base = if (markColor >= 0) markColor else field
            if (isHovered) {
                return base + Constants.HOVER_HSL_ALPHA
            }
            return base
        }

    var seColor: Int = seColor
        private set
        get() {
            // 12345678 = an empty tile that isn't drawn (common on upper floors): keep it hidden,
            // otherwise hovering it turns it into a visible white tile that stays behind
            if (field == 12345678) return field
            if (isSelected) {
                return Constants.SELECTED_HSL
            }
            val base = if (markColor >= 0) markColor else field
            if (isHovered) {
                return base + Constants.HOVER_HSL_ALPHA
            }
            return base
        }

    var neColor: Int = neColor
        private set
        get() {
            // 12345678 = an empty tile that isn't drawn (common on upper floors): keep it hidden,
            // otherwise hovering it turns it into a visible white tile that stays behind
            if (field == 12345678) return field
            if (isSelected) {
                return Constants.SELECTED_HSL
            }
            val base = if (markColor >= 0) markColor else field
            if (isHovered) {
                return base + Constants.HOVER_HSL_ALPHA
            }
            return base
        }

    var nwColor: Int = nwColor
        private set
        get() {
            // 12345678 = an empty tile that isn't drawn (common on upper floors): keep it hidden,
            // otherwise hovering it turns it into a visible white tile that stays behind
            if (field == 12345678) return field
            if (isSelected) {
                return Constants.SELECTED_HSL
            }
            val base = if (markColor >= 0) markColor else field
            if (isHovered) {
                return base + Constants.HOVER_HSL_ALPHA
            }
            return base
        }

    /** Tint used by the tile flag view, -1 = none. */
    var markColor: Int = -1
        set(value) {
            if (value == field) return
            field = value
            notifyObservers(EventType.SELECT)
        }

    var computeObj: ComputeObj = ComputeObj()

    override fun draw(modelBuffers: ModelBuffers, sceneX: Int, sceneY: Int, height: Int, objType: Int) {
        val x: Int = sceneX * Constants.LOCAL_TILE_SIZE
        val z: Int = sceneY * Constants.LOCAL_TILE_SIZE
        val b: GpuIntBuffer = modelBuffers.modelBufferUnordered
        modelBuffers.incUnorderedModels()
        b.ensureCapacity(13)

        computeObj.idx = modelBuffers.targetBufferOffset
        computeObj.flags = FLAG_SCENE_BUFFER
        computeObj.x = x
        computeObj.z = z
        computeObj.pickerId = modelBuffers.calcPickerId(sceneX, sceneY, objType)
        b.buffer.put(computeObj.toArray())

        modelBuffers.addTargetBufferOffset(computeObj.size * 3)
    }

    override fun drawDynamic(modelBuffers: ModelBuffers, sceneX: Int, sceneY: Int, height: Int) {
        TODO("Not yet implemented")
    }

    fun recompute(modelBuffers: ModelBuffers) {
        val b: GpuIntBuffer = modelBuffers.modelBufferUnordered
        modelBuffers.incUnorderedModels()
        b.ensureCapacity(13)
        computeObj.flags = 0

        b.buffer.put(computeObj.toArray())
    }

    override fun clearDraw(modelBuffers: ModelBuffers) {
        val b: GpuIntBuffer = modelBuffers.modelBufferUnordered
        modelBuffers.incUnorderedModels()
        b.ensureCapacity(13)

        computeObj.x = Int.MAX_VALUE
        computeObj.y = Int.MAX_VALUE
        computeObj.z = Int.MAX_VALUE
        b.buffer.put(computeObj.toArray())
    }
}