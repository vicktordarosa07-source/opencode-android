package com.opencode.desktop

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object BinaryManager {
    private const val TAG = "BinaryManager"
    private const val PREFS = "opencode_binary_prefs"
    private const val KEY_HASH = "last_hash"
    private const val KEY_VER = "last_ver"
    private const val EXEC_NAME = "opencode"

    private fun candidates(): List<String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when (abi) {
            "arm64-v8a" -> listOf("opencode-android-arm64", "opencode")
            "armeabi-v7a" -> listOf("opencode-android-arm", "opencode")
            "x86_64" -> listOf("opencode-android-amd64", "opencode")
            "x86" -> listOf("opencode-android-386", "opencode")
            else -> listOf("opencode", "opencode-android-arm64")
        }
    }

    @Throws(Exception::class)
    fun extractIfNeeded(context: Context): File {
        val dest = File(context.filesDir, EXEC_NAME)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val assets = context.assets.list("")?.toList() ?: emptyList()
        Log.i(TAG, "Assets: $assets candidates: ${candidates()} ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        var chosen: String? = null
        var hash: String? = null
        for (c in candidates()) if (assets.contains(c)) { chosen = c; hash = hashAsset(context, c); break }
        if (chosen == null) throw IllegalStateException("Nenhum binário em assets. Esperado um de ${candidates()} assets=$assets. Rode ./scripts/build-opencode-android.sh")
        val lastHash = prefs.getString(KEY_HASH, null)
        val lastVer = prefs.getInt(KEY_VER, -1)
        val curVer = getVer(context)
        val need = !dest.exists() || dest.length()==0L || lastHash != hash || lastVer != curVer || !dest.canExecute()
        if (need) {
            Log.i(TAG, "Extraindo $chosen -> ${dest.absolutePath} hash=$hash")
            extract(context, chosen, dest)
            chmod(dest)
            prefs.edit().putString(KEY_HASH, hash).putInt(KEY_VER, curVer).apply()
            Log.i(TAG, "Extraído ${dest.length()} bytes exec=${dest.canExecute()}")
        } else {
            chmod(dest)
            Log.i(TAG, "Cache válido reutilizado")
        }
        verifyElf(dest)
        return dest
    }

    private fun extract(ctx: Context, asset: String, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        ctx.assets.open(asset).use { inp ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(8192)
                var r: Int
                var tot=0L
                while (inp.read(buf).also { r=it } != -1) { out.write(buf,0,r); tot+=r }
                out.fd.sync()
                Log.i(TAG, "copiado $tot bytes")
            }
        }
    }

    private fun chmod(f: File): Boolean {
        return try {
            f.setExecutable(true,true); f.setReadable(true,true)
            try { Os.chmod(f.absolutePath, 448); true } catch (e: Exception) {
                Runtime.getRuntime().exec(arrayOf("chmod","700",f.absolutePath)).waitFor(); f.canExecute()
            }
        } catch (e: Exception) { Log.e(TAG,"chmod fail",e); false }
    }

    private fun hashAsset(ctx: Context, a: String): String = try {
        val d = MessageDigest.getInstance("SHA-256")
        ctx.assets.open(a).use { inp -> val b=ByteArray(8192); var r:Int; while(inp.read(b).also{r=it}!=-1) d.update(b,0,r) }
        d.digest().joinToString(""){"%02x".format(it)}.take(16)
    } catch(e:Exception) { "nohash-${System.currentTimeMillis()}" }

    private fun getVer(ctx: Context): Int = try {
        val p=ctx.packageManager.getPackageInfo(ctx.packageName,0)
        if(Build.VERSION.SDK_INT>=28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    } catch(e:Exception) { -1 }

    private fun verifyElf(f: File) {
        try {
            val h=ByteArray(4); f.inputStream().use{it.read(h)}
            val isElf=h[0]==0x7F.toByte()&&h[1]=='E'.code.toByte()&&h[2]=='L'.code.toByte()&&h[3]=='F'.code.toByte()
            Log.i(TAG,"ELF ok=$isElf ${h.joinToString(" "){"%02x".format(it)}}")
        } catch(e:Exception){ Log.w(TAG,"elf check fail ${e.message}") }
    }
}
