package com.winlator.renderer;

import android.opengl.EGL14;
import android.opengl.GLES20;
import android.util.Log;
import androidx.annotation.Keep;
import com.winlator.xserver.Drawable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class GPUImage extends NativeTexture {
    private static final String TAG = "GPUImage";
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private ByteBuffer virtualData;
    private short stride;
    private static boolean supported = false;

    static {
        System.loadLibrary("extras");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) return;
        super.allocateTexture(width, height, null);
        if (hardwareBufferPtr != 0) {
            imageKHRPtr = createImageKHR(hardwareBufferPtr, textureId);
            if (imageKHRPtr == 0) {
                System.err.println("Error: Failed to create EGL image");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        }
    }

    @Override
    public void updateFromDrawable(Drawable drawable) {
        if (!isAllocated()) allocateTexture(drawable.width, drawable.height, null);
        needsUpdate = false;
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    @Override
    public void destroy() {
        if (imageKHRPtr != 0) {
            destroyImageKHR(imageKHRPtr);
            imageKHRPtr = 0;
        }
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
        virtualData = null;
        super.destroy();
    }

    public static boolean isSupported() {
        return supported;
    }

    /**
     * Whether window contents can live in a GPUImage. Creating one is not
     * enough: a GPUImage is written through a mapping that stays locked for
     * its whole life and is sampled through an EGLImage without ever being
     * unlocked, which shows the pixels only where the CPU mapping and the
     * GPU's copy are the same memory. On an emulated gralloc (BlueStacks'
     * gralloc_bst: an ashmem region beside a host colour buffer) the host
     * copy is refreshed only on unlock, so every window the X server moved
     * into a GPUImage (PresentExtension.selectInput, which Mesa's X11 WSI
     * reaches for every Vulkan swapchain) was drawn black by the GL
     * renderer. So, when a GL context is current, a pattern is written
     * through the mapping and read back through the texture; if it does
     * not arrive, windows keep plain textures that are uploaded each frame.
     */
    public static void checkIsSupported() {
        final short size = 8;
        GPUImage gpuImage = new GPUImage(size, size);
        gpuImage.allocateTexture(size, size, null);
        boolean created = gpuImage.hardwareBufferPtr != 0 && gpuImage.imageKHRPtr != 0 && gpuImage.virtualData != null;
        boolean coherent = created && cpuWritesReachGpu(gpuImage);
        supported = created && coherent;
        if (created && !coherent) {
            Log.w(TAG, "CPU writes to a locked AHardwareBuffer do not reach the GPU here; window contents are uploaded instead");
        }
        gpuImage.destroy();
    }

    /**
     * True unless a readback proves the CPU's writes invisible to the GPU.
     * Without a current GL context (the Vulkan renderer checks from its own
     * thread) or with a texture that cannot be read back, nothing is proven
     * and creation alone answers, as before.
     */
    private static boolean cpuWritesReachGpu(GPUImage image) {
        if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) return true;
        // B, G, R, A in memory: opaque grey-green, unlike a zeroed buffer.
        final int bgra = 0xFF408040;
        ByteBuffer data = image.virtualData.order(ByteOrder.LITTLE_ENDIAN);
        while (data.remaining() >= 4) data.putInt(bgra);
        data.rewind();

        int[] previous = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previous, 0);
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, image.textureId, 0);
        boolean readable = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        if (readable) GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previous[0]);
        GLES20.glDeleteFramebuffers(1, fbo, 0);
        if (!readable) {
            Log.i(TAG, "coherence probe: the texture cannot be read back; keeping GPUImage");
            return true;
        }
        int r = pixel.get(0) & 0xFF, g = pixel.get(1) & 0xFF, b = pixel.get(2) & 0xFF;
        boolean seen = Math.abs(r - 0x40) <= 8 && Math.abs(g - 0x80) <= 8 && Math.abs(b - 0x40) <= 8;
        Log.i(TAG, "coherence probe: read " + r + "," + g + "," + b + (seen ? ", the GPU sees CPU writes" : ", the GPU does not see CPU writes"));
        return seen;
    }

    public long getHardwareBufferPtr() {
        return this.hardwareBufferPtr;
    }

    public void lock() {
        if (hardwareBufferPtr != 0 && virtualData == null) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
        }
    }

    public int unlock() {
        if (hardwareBufferPtr != 0 && virtualData != null) {
            int fenceFd = unlockHardwareBuffer(hardwareBufferPtr);
            virtualData = null;
            return fenceFd;
        }
        return -1;
    }

    private native long hardwareBufferFromSocket(int fd);

    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native int unlockHardwareBuffer(long hardwareBufferPtr);

    private native long createImageKHR(long hardwareBufferPtr, int textureId);

    private native void destroyImageKHR(long imageKHRPtr);
}
