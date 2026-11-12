package one.lmk.wordcraft.bridge

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * JNI Bridge to WordCraft's native Rust core engine.
 */
object WordCraftBridge {
    init {
        try {
            System.loadLibrary("wordcraft_android_bridge")
            initBridge()
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun initBridge()
    external fun createSession(docBytes: ByteArray?): Long
    external fun destroySession(handle: Long)
    external fun getPageCount(handle: Long): Int
    external fun getPageWidth(handle: Long, pageIdx: Int): Float
    external fun getPageHeight(handle: Long, pageIdx: Int): Float
    external fun renderPage(handle: Long, pageIdx: Int, scale: Float, darkMode: Boolean): ByteArray?
    external fun runCommand(handle: Long, commandId: String, paramsJson: String?): String?
    external fun saveDocx(handle: Long): ByteArray?
    external fun getPlainText(handle: Long): String?

    /**
     * Helper to render page directly into an Android ARGB_8888 Bitmap.
     */
    fun renderPageToBitmap(handle: Long, pageIdx: Int, scale: Float, darkMode: Boolean): Bitmap? {
        val bytes = renderPage(handle, pageIdx, scale, darkMode) ?: return null
        val w = (getPageWidth(handle, pageIdx) * scale).toInt().coerceAtLeast(1)
        val h = (getPageHeight(handle, pageIdx) * scale).toInt().coerceAtLeast(1)
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val buffer = ByteBuffer.wrap(bytes)
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
