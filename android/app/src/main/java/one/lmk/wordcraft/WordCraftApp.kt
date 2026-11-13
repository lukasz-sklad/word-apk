package one.lmk.wordcraft

import android.app.Application
import android.system.Os
import java.io.File
import java.io.FileOutputStream

class WordCraftApp : Application() {
    companion object {
        private const val BUNDLED_PL_PACK_ASSET_DIR = "language-packs/pl"
        private const val BUNDLED_PL_PACK_VERSION = "20467b63558f04fe075e83aaea501bd10f7fa684"
    }

    override fun onCreate() {
        super.onCreate()
        configureLocaleEnv()
        unpackBundledPolishPack()
    }

    private fun configureLocaleEnv() {
        try {
            val locales = resources.configuration.locales
            val tags = locales.toLanguageTags()
            if (tags.isNotEmpty()) {
                Os.setenv("WORDCRAFT_LANGUAGE", tags, false)
            }
            if (!locales.isEmpty) {
                val primary = locales[0]?.toLanguageTag().orEmpty()
                if (primary.isNotEmpty()) {
                    Os.setenv("WORDCRAFT_REGION", primary, false)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun unpackBundledPolishPack() {
        try {
            val targetDir = File(File(filesDir, "font-store/language-packs"), "pl")
            val stampFile = File(targetDir, ".bundled-version")
            if (stampFile.exists() &&
                stampFile.readText().trim() == BUNDLED_PL_PACK_VERSION &&
                File(targetDir, "spelling.dic").exists() &&
                File(targetDir, "spelling.aff").exists() &&
                File(targetDir, "pack.toml").exists()
            ) {
                return
            }
            copyAssetDir(BUNDLED_PL_PACK_ASSET_DIR, targetDir)
            writeAtomic(stampFile, BUNDLED_PL_PACK_VERSION.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val entries = assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            destDir.parentFile?.mkdirs()
            val tmp = File(destDir.parentFile, ".${destDir.name}.tmp")
            assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                }
            }
            if (!tmp.renameTo(destDir)) {
                tmp.copyTo(destDir, overwrite = true)
                tmp.delete()
            }
            return
        }
        destDir.mkdirs()
        for (entry in entries) {
            copyAssetDir("$assetPath/$entry", File(destDir, entry))
        }
    }

    private fun writeAtomic(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
