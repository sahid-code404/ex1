package com.sahidcode404.camera.core.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeRawMerger {
    init {
        System.loadLibrary("camera_core")
    }

    data class MergeOutcome(
        val output: ByteBuffer,
        val referenceIndex: Int,
        val acceptedFrames: Int,
        val meanAlignmentCost: Float,
        val referenceSharpness: Float,
    )

    fun mergePackedRaw(
        frames: Array<ByteBuffer>,
        width: Int,
        height: Int,
        exposureNs: LongArray,
        iso: IntArray,
        blackLevels: IntArray,
        whiteLevel: Int,
        cfa: Int,
    ): MergeOutcome {
        require(frames.isNotEmpty())
        require(frames.size == exposureNs.size && frames.size == iso.size)
        val output = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
        val diagnostics = FloatArray(4)
        val accepted = nativeMergePackedRaw(
            frames,
            width,
            height,
            exposureNs,
            iso,
            blackLevels,
            whiteLevel,
            cfa,
            output,
            diagnostics,
        )
        check(accepted > 0) { "Native RAW merge failed: $accepted" }
        output.rewind()
        return MergeOutcome(
            output = output,
            referenceIndex = diagnostics[0].toInt().coerceIn(0, frames.lastIndex),
            acceptedFrames = diagnostics[1].toInt().coerceAtLeast(1),
            meanAlignmentCost = diagnostics[2],
            referenceSharpness = diagnostics[3],
        )
    }

    fun renderRawPreview(
        raw: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        outWidth: Int,
        outHeight: Int,
        blackLevels: IntArray,
        whiteLevel: Int,
        cfa: Int,
    ): IntArray = nativeRenderRawPreview(
        raw,
        width,
        height,
        rowStride,
        pixelStride,
        outWidth,
        outHeight,
        blackLevels,
        whiteLevel,
        cfa,
    )

    @JvmStatic
    private external fun nativeMergePackedRaw(
        frames: Array<ByteBuffer>,
        width: Int,
        height: Int,
        exposureNs: LongArray,
        iso: IntArray,
        blackLevels: IntArray,
        whiteLevel: Int,
        cfa: Int,
        output: ByteBuffer,
        diagnostics: FloatArray,
    ): Int

    @JvmStatic
    private external fun nativeRenderRawPreview(
        raw: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        outWidth: Int,
        outHeight: Int,
        blackLevels: IntArray,
        whiteLevel: Int,
        cfa: Int,
    ): IntArray
}
