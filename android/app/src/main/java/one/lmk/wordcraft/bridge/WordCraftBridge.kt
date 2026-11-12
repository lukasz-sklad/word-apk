package one.lmk.wordcraft.bridge

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    external fun createSession(docBytes: ByteArray?, filename: String?): Long
    external fun destroySession(handle: Long)
    external fun getPageCount(handle: Long): Int
    external fun getPageWidth(handle: Long, pageIdx: Int): Float
    external fun getPageHeight(handle: Long, pageIdx: Int): Float
    external fun renderPage(handle: Long, pageIdx: Int, scale: Float, darkMode: Boolean): ByteArray?
    external fun getCaret(handle: Long): FloatArray?
    external fun setCaret(handle: Long, pageIdx: Int, x: Float, y: Float)
    external fun insertText(handle: Long, text: String)
    external fun deleteBackward(handle: Long)
    external fun insertParagraph(handle: Long)
    external fun runCommand(handle: Long, commandId: String, paramsJson: String?): String?
    external fun saveDocx(handle: Long): ByteArray?
    external fun getPlainText(handle: Long): String?

    data class CaretInfo(val pageIdx: Int, val x: Float, val top: Float, val height: Float)

    fun getCaretInfo(handle: Long): CaretInfo? {
        val arr = getCaret(handle) ?: return null
        if (arr.size < 4) return null
        return CaretInfo(arr[0].toInt(), arr[1], arr[2], arr[3])
    }

    /**
     * Renders page directly into an Android ARGB_8888 Bitmap using the exact dimensions from Rust.
     */
    fun renderPageToBitmap(handle: Long, pageIdx: Int, scale: Float, darkMode: Boolean): Bitmap? {
        val bytes = renderPage(handle, pageIdx, scale, darkMode) ?: return null
        if (bytes.size < 8) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            val pixelBytes = width * height * 4
            if (width <= 0 || height <= 0 || bytes.size < 8 + pixelBytes) {
                return null
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.position(8)
            val pixelBuffer = buffer.slice()
            pixelBuffer.limit(pixelBytes)
            bitmap.copyPixelsFromBuffer(pixelBuffer)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
