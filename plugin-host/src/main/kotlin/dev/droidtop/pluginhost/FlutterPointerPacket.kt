package dev.droidtop.pluginhost

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Touches for a plugin's Flutter screen, packed for the engine without asking Android about the input device
 * (Droidtop/tracker#511). Flutter's own `AndroidTouchProcessor` reads every pointer's pressure range from
 * `MotionEvent.getDevice()`, which goes through the input service; an isolated (contained) plugin process cannot
 * reach that service, so the first touch killed it. This is that class's touch path, ported from the engine revision
 * droidtop pins (flutter_embedding_release 1.0.0-af7e796e, `AndroidTouchProcessor.java`, Copyright 2013 The Flutter
 * Authors, BSD licence) field for field into the engine's `pointer_data.h` layout, with the pressure range droidtop
 * already knows: Android reports pressure as 0 to 1 for a touch screen. Trackpad pans and scroll wheels are not
 * packed: droidtop hands a plugin's screen touches and mouse buttons only.
 *
 * Pure: [TouchEvent] is the parts of a `MotionEvent` the packet reads ([ScreenTouches.of] builds it).
 */
object FlutterPointerPacket {
    /** Fields per pointer and bytes per field: the engine's `kPointerDataFieldCount` and its 8-byte fields. */
    const val FIELD_COUNT = 36
    const val BYTES_PER_FIELD = 8

    // PointerChange
    const val CANCEL = 0
    const val REMOVE = 2
    const val HOVER = 3
    const val DOWN = 4
    const val MOVE = 5
    const val UP = 6

    // PointerDeviceKind
    const val KIND_TOUCH = 0
    const val KIND_MOUSE = 1
    const val KIND_STYLUS = 2
    const val KIND_INVERTED_STYLUS = 3
    const val KIND_UNKNOWN = 5

    private const val FLAG_BATCHED = 1
    private const val FLAG_MULTIPLE = 2
    private const val MULTIPLE_POINTER_COUNT_SHIFT = 8
    private const val TOOL_TYPE_BITS = 3
    private const val TOOL_TYPE_MASK = 7

    // MotionEvent's constants, restated so the packer runs off the device too.
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6
    const val ACTION_HOVER_MOVE = 7
    const val TOOL_TYPE_FINGER = 1
    const val TOOL_TYPE_STYLUS = 2
    const val TOOL_TYPE_MOUSE = 3
    const val TOOL_TYPE_ERASER = 4

    /** One pointer of a touch, in the screen's own pixels. */
    data class Pointer(
        val id: Int,
        val toolType: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val size: Float,
        val toolMajor: Float,
        val toolMinor: Float,
        val orientation: Float,
        val distance: Float = 0f,
        val tilt: Float = 0f,
    )

    /** A touch as the packet reads it: Android's masked action, its pointer index, the time and every pointer. */
    data class TouchEvent(val actionMasked: Int, val actionIndex: Int, val eventTimeMs: Long, val buttonState: Int, val pointers: List<Pointer>)

    /** The engine packet for [event], positioned at its end, or null for an action a touch screen does not send. */
    fun pack(event: TouchEvent): ByteBuffer? {
        val change = changeFor(event.actionMasked) ?: return null
        val single = event.actionMasked == ACTION_DOWN || event.actionMasked == ACTION_POINTER_DOWN
        val multiple = !single && (event.actionMasked == ACTION_UP || event.actionMasked == ACTION_POINTER_UP)
        val index = event.actionIndex.coerceIn(0, (event.pointers.size - 1).coerceAtLeast(0))
        val actionPointer = event.pointers.getOrNull(index) ?: return null
        val remove = multiple && kindOf(actionPointer.toolType) == KIND_TOUCH
        val count = event.pointers.size + if (remove) 1 else 0
        val packet = ByteBuffer.allocateDirect(count * FIELD_COUNT * BYTES_PER_FIELD).order(ByteOrder.LITTLE_ENDIAN)
        when {
            single -> put(packet, event, index, change, 0)
            multiple -> {
                // Other fingers' positions in an UP are moves, sent first and marked batched; the UP comes last.
                event.pointers.forEachIndexed { p, pointer ->
                    if (p != index && pointer.toolType == TOOL_TYPE_FINGER) put(packet, event, p, MOVE, FLAG_BATCHED)
                }
                put(packet, event, index, change, 0)
                // A touch ends its pointer, so the engine never sees a hover between two touches.
                if (remove) put(packet, event, index, REMOVE, 0)
            }
            else -> {
                val data = FLAG_MULTIPLE or (event.pointers.size shl MULTIPLE_POINTER_COUNT_SHIFT)
                event.pointers.indices.forEach { p -> put(packet, event, p, change, data) }
            }
        }
        return packet
    }

    fun changeFor(actionMasked: Int): Int? = when (actionMasked) {
        ACTION_DOWN, ACTION_POINTER_DOWN -> DOWN
        ACTION_UP, ACTION_POINTER_UP -> UP
        ACTION_MOVE -> MOVE
        ACTION_HOVER_MOVE -> HOVER
        ACTION_CANCEL -> CANCEL
        else -> null
    }

    fun kindOf(toolType: Int): Int = when (toolType) {
        TOOL_TYPE_FINGER -> KIND_TOUCH
        TOOL_TYPE_STYLUS -> KIND_STYLUS
        TOOL_TYPE_MOUSE -> KIND_MOUSE
        TOOL_TYPE_ERASER -> KIND_INVERTED_STYLUS
        else -> KIND_UNKNOWN
    }

    /** One pointer's 36 fields, in `pointer_data.h`'s order. */
    private fun put(packet: ByteBuffer, event: TouchEvent, index: Int, change: Int, platformData: Int) {
        val p = event.pointers[index]
        val kind = kindOf(p.toolType)
        val buttons: Long = when (kind) {
            KIND_MOUSE -> (event.buttonState and 0x1F).toLong()
            KIND_STYLUS -> ((event.buttonState shr 4) and 0xF).toLong()
            else -> 0L
        }
        val device = (p.id shl TOOL_TYPE_BITS) or (p.toolType and TOOL_TYPE_MASK)
        packet.putLong(0) // motion event id: none, droidtop's events are not tracked for platform views
        packet.putLong(event.eventTimeMs * 1000) // time stamp, microseconds
        packet.putLong(change.toLong())
        packet.putLong(kind.toLong())
        packet.putLong(0) // signal kind: none
        packet.putLong(device.toLong())
        packet.putLong(0) // pointer identifier, made by the engine
        packet.putDouble(p.x.toDouble())
        packet.putDouble(p.y.toDouble())
        packet.putDouble(0.0) // physical delta x, made by the engine
        packet.putDouble(0.0) // physical delta y
        packet.putLong(buttons)
        packet.putLong(0) // obscured
        packet.putLong(0) // synthesized
        packet.putDouble(p.pressure.toDouble())
        packet.putDouble(0.0) // pressure min: what Android reports for a touch screen, without asking the device
        packet.putDouble(1.0) // pressure max
        packet.putDouble(if (kind == KIND_STYLUS) p.distance.toDouble() else 0.0)
        packet.putDouble(0.0) // distance max
        packet.putDouble(p.size.toDouble())
        packet.putDouble(p.toolMajor.toDouble()) // radius major
        packet.putDouble(p.toolMinor.toDouble()) // radius minor
        packet.putDouble(0.0) // radius min
        packet.putDouble(0.0) // radius max
        packet.putDouble(p.orientation.toDouble())
        packet.putDouble(if (kind == KIND_STYLUS) p.tilt.toDouble() else 0.0)
        packet.putLong(platformData.toLong())
        packet.putDouble(0.0) // scroll delta x
        packet.putDouble(0.0) // scroll delta y
        packet.putDouble(0.0) // pan x
        packet.putDouble(0.0) // pan y
        packet.putDouble(0.0) // pan delta x
        packet.putDouble(0.0) // pan delta y
        packet.putDouble(1.0) // scale
        packet.putDouble(0.0) // rotation
        packet.putLong(0) // view id: the implicit view
    }
}

/** A `MotionEvent` as [FlutterPointerPacket] reads it, without `getDevice()`. */
internal object ScreenTouches {
    fun of(event: android.view.MotionEvent): FlutterPointerPacket.TouchEvent = FlutterPointerPacket.TouchEvent(
        actionMasked = event.actionMasked,
        actionIndex = event.actionIndex,
        eventTimeMs = event.eventTime,
        buttonState = event.buttonState,
        pointers = List(event.pointerCount) { i ->
            FlutterPointerPacket.Pointer(
                id = event.getPointerId(i),
                toolType = event.getToolType(i),
                x = event.getX(i),
                y = event.getY(i),
                pressure = event.getPressure(i),
                size = event.getSize(i),
                toolMajor = event.getToolMajor(i),
                toolMinor = event.getToolMinor(i),
                orientation = event.getAxisValue(android.view.MotionEvent.AXIS_ORIENTATION, i),
                distance = event.getAxisValue(android.view.MotionEvent.AXIS_DISTANCE, i),
                tilt = event.getAxisValue(android.view.MotionEvent.AXIS_TILT, i),
            )
        },
    )
}
