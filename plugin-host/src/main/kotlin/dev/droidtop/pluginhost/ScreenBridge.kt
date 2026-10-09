package dev.droidtop.pluginhost

import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/**
 * The two ends of a contained plugin's own screen (`ui.main`, docs/plugin-api.md 5.3, "ui.main in the sandbox";
 * `native/src/screen_bridge.c`).
 *
 * An isolated process cannot present into a Surface: the buffers come from the graphics allocator HAL, whose fds only
 * its clients may use, and it is never one (emulator-5560: `avc: denied { use } ... hal_graphics_allocator_default ...
 * tclass=fd`, then `dequeueBuffer: IGraphicBufferProducer::requestBuffer failed`). So droidtop allocates two frames of
 * shared memory ([create], in its own process), hands them to the plugin's process ([attach]), where the Flutter engine's
 * software rasteriser draws into them through a stand-in window, and copies each finished frame into the real Surface
 * when [onFrame] says one is ready.
 */
internal object ScreenBridge {
    init {
        System.loadLibrary("droidtoppy")
    }

    /** Bytes for two RGBA frames of [width] x [height]. */
    fun capacityFor(width: Int, height: Int): Long = 2L * width * height * 4

    // ---- droidtop's side ----

    /** Shared memory for the frames; the descriptor goes to the plugin's process, the mapping stays here. Null when it could not be made. */
    fun create(capacity: Long): Pair<ParcelFileDescriptor, ByteBuffer>? {
        val fd = nativeCreate(capacity)
        if (fd < 0) return null
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val map = nativeMap(pfd.fd, capacity) ?: run {
            pfd.close()
            return null
        }
        return pfd to map
    }

    fun unmap(buffer: ByteBuffer) = nativeUnmap(buffer)

    // ---- the plugin's side ----

    @Volatile private var listener: ((index: Int, width: Int, height: Int) -> Unit)? = null

    /** Maps the frames droidtop handed over (the descriptor is taken over) and routes each finished frame to [onFrame]. */
    fun attach(frames: ParcelFileDescriptor, capacity: Long, width: Int, height: Int, onFrame: (index: Int, width: Int, height: Int) -> Unit): Boolean {
        listener = onFrame
        return nativeAttach(frames.detachFd(), capacity, width, height)
    }

    fun resize(width: Int, height: Int) = nativeResize(width, height)

    fun detach() {
        listener = null
        nativeDetach()
    }

    /** Called by `screen_bridge.c` on the engine's raster thread when a frame is posted. */
    @JvmStatic
    fun onFrame(index: Int, width: Int, height: Int) {
        listener?.invoke(index, width, height)
    }

    @JvmStatic private external fun nativeCreate(capacity: Long): Int

    @JvmStatic private external fun nativeMap(fd: Int, capacity: Long): ByteBuffer?

    @JvmStatic private external fun nativeUnmap(buffer: ByteBuffer)

    @JvmStatic private external fun nativeAttach(fd: Int, capacity: Long, width: Int, height: Int): Boolean

    @JvmStatic private external fun nativeResize(width: Int, height: Int)

    @JvmStatic private external fun nativeDetach()
}
