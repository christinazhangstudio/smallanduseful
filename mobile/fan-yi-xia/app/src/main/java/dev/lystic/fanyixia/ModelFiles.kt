package dev.lystic.fanyixia

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal object WhisperNative {
    init { System.loadLibrary("fanyixia") }
    external fun transcribe(modelPath: String, samples: FloatArray, count: Int): ByteArray
}

internal class ModelFiles(context: Context) {
    val model = File(context.noBackupFilesDir, "ggml-base.bin")

    // Official ggerganov/whisper.cpp Git-LFS SHA-256 and length for multilingual base.
    // https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main?expand=true
    private val sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    private val size = 147951465L
    private val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin"

    fun verified(): Boolean {
        if (!model.isFile || model.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == sha256
    }

    fun prepare(progress: (Int) -> Unit) {
        if (verified()) { progress(100); return }
        model.parentFile?.mkdirs()
        val partial = File(model.parentFile, "ggml-base.bin.part")
        val connection = openModelConnection()
        try {
            require(connection.responseCode == 200) { "Model download returned HTTP ${connection.responseCode}. Retry preparation." }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPercent = -1
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        require(downloaded <= size) { "Model download exceeded its official size." }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val percent = (downloaded * 100 / size).toInt()
                        if (percent != lastPercent) { progress(percent); lastPercent = percent }
                    }
                    output.fd.sync()
                }
            }
            require(downloaded == size && digest.digest().toHex() == sha256) {
                "Model checksum mismatch; incomplete or untrusted download removed. Retry preparation."
            }
            Files.move(partial.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun openModelConnection(): HttpsURLConnection {
        var target = URL(url)
        repeat(6) {
            require(target.protocol == "https") { "Refusing an insecure model download." }
            val connection = target.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "Model download redirect has no location." }
                target = URL(target, location)
            } else {
                return connection
            }
        }
        error("Too many model download redirects.")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
