package com.jiangdg.ausbc.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.natives.YUVUtils
import java.nio.ByteBuffer

/** H.264 encoder with configurable frame rate and bitrate. */
class H264EncodeProcessor(
    val width: Int,
    val height: Int,
    private val gLESRender: Boolean = false,
    private val isPortrait: Boolean = true,
    private val frameRate: Int = 30,
    private val bitRate: Int = 0
) : AbstractProcessor(true) {
    private var mReadyListener: OnEncodeReadyListener? = null

    override fun getThreadName(): String = TAG

    override fun handleStartEncode() {
        try {
            val mediaFormat = MediaFormat.createVideoFormat(MIME, width, height)
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceIn(1, 120))
            mediaFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                if (bitRate > 0) bitRate else getEncodeBitrate(width, height, frameRate)
            )
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL)
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, getSupportColorFormat())
            mMediaCodec = MediaCodec.createEncoderByType(MIME)
            mMediaCodec?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (gLESRender) mReadyListener?.onReady(mMediaCodec?.createInputSurface())
            mMediaCodec?.start()
            mEncodeState.set(true)
            doEncodeData()
            Logger.i(TAG, "H264 encoder ready ${width}x${height}@${frameRate} ${if (bitRate > 0) bitRate else getEncodeBitrate(width, height, frameRate)}bps")
        } catch (e: Exception) {
            Logger.e(TAG, "start h264 media codec failed, err = ${e.localizedMessage}", e)
        }
    }

    override fun handleStopEncode() {
        try {
            mEncodeState.set(false)
            mMediaCodec?.stop()
            mMediaCodec?.release()
        } catch (e: Exception) {
            Logger.e(TAG, "Stop mediaCodec failed, err = ${e.localizedMessage}", e)
        } finally {
            mRawDataQueue.clear()
            mMediaCodec = null
        }
    }

    override fun getPTSUs(bufferSize: Int): Long = System.nanoTime() / 1000L

    override fun processOutputData(encodeData: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): Pair<IEncodeDataCallBack.DataType, ByteBuffer> {
        val type = when (bufferInfo.flags) {
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG -> IEncodeDataCallBack.DataType.H264_SPS
            MediaCodec.BUFFER_FLAG_KEY_FRAME -> IEncodeDataCallBack.DataType.H264_KEY
            else -> IEncodeDataCallBack.DataType.H264
        }
        return Pair(type, encodeData)
    }

    override fun processInputData(data: ByteArray): ByteArray? {
        return if (gLESRender) null else data.apply {
            if (size != width * height * 3 / 2) return null
            if (isPortrait) YUVUtils.nativeRotateNV21(data, width, height, 90)
            YUVUtils.nv21ToYuv420sp(data, width, height)
        }
    }

    fun setOnEncodeReadyListener(listener: OnEncodeReadyListener) { mReadyListener = listener }
    private fun getSupportColorFormat(): Int = if (gLESRender) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private fun getEncodeBitrate(width: Int, height: Int, fps: Int): Int {
        var bitRate = width * height * 20 * 3 * 0.07F
        if (width >= 1920 || height >= 1920) bitRate *= 0.75F
        else if (width >= 1280 || height >= 1280) bitRate *= 1.2F
        else if (width >= 640 || height >= 640) bitRate *= 1.4F
        bitRate *= fps.coerceIn(1, 120).toFloat() / 30f
        return bitRate.toInt().coerceAtLeast(1_000_000)
    }

    fun getEncodeWidth() = width
    fun getEncodeHeight() = height
    fun getEncodeFrameRate() = frameRate
    fun getEncodeBitRate() = if (bitRate > 0) bitRate else getEncodeBitrate(width, height, frameRate)

    interface OnEncodeReadyListener { fun onReady(surface: Surface?) }

    companion object {
        private const val TAG = "H264EncodeProcessor"
        private const val MIME = "video/avc"
        private const val KEY_FRAME_INTERVAL = 1
    }
}
