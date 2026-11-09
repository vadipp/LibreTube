package com.github.libretube.helpers

import android.os.Build
import android.content.Context
import androidx.core.content.ContextCompat

object DisplayHelper {
    /**
     * Detect whether the device supports HDR as the ExoPlayer doesn't handle it properly
     * Returns false below SDK 24
     */
    fun supportsHdr(context: Context): Boolean {
        val display = ContextCompat.getDisplayOrDefault(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            display.isHdr
        } else {
            display.hdrCapabilities.supportedHdrTypes.isNotEmpty()
        }
    }
}
