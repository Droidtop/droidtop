package dev.droidtop.shell.gamepad.theme

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val WAVE_FORMAT_PCM = 1
private const val WAVE_FORMAT_IEEE_FLOAT = 3

/** Larger than this and the sample is left alone: SoundPool refuses samples over 1 MB anyway. */
private const val MAX_NORMALIZED_DATA_BYTES = 8L * 1024L * 1024L

/**
 * Whether SoundPool would mis-play this file: it treats every sample as
 * 16-bit PCM, so 8-bit, 24-bit and 32-bit integer samples and float samples
 * come out as noise (a chirp, then static like rain, in the owner's words on
 * Droidtop/tracker#160, whose theme's launch sample is 32-bit). Anything else
 * (16-bit PCM, or a compressed format the pool decodes itself) is left alone.
 */
internal fun WavInfo.needsSixteenBit(): Boolean = when (encoding) {
    WAVE_FORMAT_PCM -> bitsPerSample == 8 || bitsPerSample == 24 || bitsPerSample == 32
    WAVE_FORMAT_IEEE_FLOAT -> bitsPerSample == 32 || bitsPerSample == 64
    else -> false
} && channels in 1..8 && dataBytes in 1..MAX_NORMALIZED_DATA_BYTES

/**
 * The file SoundPool should load for [src]: [src] itself when it already is
 * 16-bit PCM (or not a wav), otherwise a 16-bit PCM copy in [cacheDir],
 * written once per source file version (named by its path, size and
 * modification time) and reused afterwards. Disk work: call it off the main
 * thread. The user's file is never touched.
 */
internal fun playableWav(src: File, cacheDir: File): File {
    val info = wavInfo(src) ?: return src
    if (!info.needsSixteenBit()) return src
    val target = File(cacheDir, "${src.absolutePath.hashCode().toUInt().toString(16)}-${src.length()}-${src.lastModified()}.wav")
    if (target.isFile && target.length() > 0) return target
    return runCatching {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, target.name + ".tmp")
        tmp.writeBytes(convertToSixteenBit(src, info))
        if (tmp.renameTo(target)) target else src.also { tmp.delete() }
    }.getOrDefault(src)
}

/** [src]'s samples as a complete 16-bit PCM .wav file, same rate and channels. */
internal fun convertToSixteenBit(src: File, info: WavInfo): ByteArray {
    val available = (src.length() - info.dataOffset).coerceAtLeast(0L)
    val dataBytes = minOf(info.dataBytes, available).toInt()
    val raw = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    java.io.RandomAccessFile(src, "r").use { file ->
        file.seek(info.dataOffset)
        file.readFully(raw.array(), 0, dataBytes)
    }
    val bytesPerSample = info.bitsPerSample / 8
    val count = dataBytes / bytesPerSample
    val outData = count * 2
    val out = ByteBuffer.allocate(44 + outData).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()).putInt(36 + outData).put("WAVE".toByteArray())
    out.put("fmt ".toByteArray()).putInt(16)
        .putShort(WAVE_FORMAT_PCM.toShort()).putShort(info.channels.toShort())
        .putInt(info.sampleRate.toInt()).putInt(info.sampleRate.toInt() * info.channels * 2)
        .putShort((info.channels * 2).toShort()).putShort(16)
    out.put("data".toByteArray()).putInt(outData)
    for (i in 0 until count) {
        val at = i * bytesPerSample
        val sample: Int = when {
            info.encoding == WAVE_FORMAT_IEEE_FLOAT && info.bitsPerSample == 32 ->
                floatToSixteen(raw.getFloat(at).toDouble())
            info.encoding == WAVE_FORMAT_IEEE_FLOAT -> floatToSixteen(raw.getDouble(at))
            info.bitsPerSample == 8 -> ((raw.get(at).toInt() and 0xFF) - 128) shl 8
            info.bitsPerSample == 24 -> (raw.get(at + 2).toInt() shl 8) or (raw.get(at + 1).toInt() and 0xFF)
            else -> raw.getInt(at) shr 16
        }
        out.putShort(sample.toShort())
    }
    return out.array()
}

private fun floatToSixteen(value: Double): Int =
    Math.round(value.coerceIn(-1.0, 1.0) * 32767.0).toInt()
