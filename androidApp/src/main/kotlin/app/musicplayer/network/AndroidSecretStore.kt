package app.musicplayer.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.musicplayer.model.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only authenticated ciphertext is written, under noBackupFilesDir; the key never leaves Keystore. */
class AndroidSecretStore(context: Context) : SecretStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "proxy-secrets")

    override suspend fun put(username: String, password: CharArray): SecretId = withContext(Dispatchers.IO) {
        require(username.isNotEmpty() && ':' !in username && '\r' !in username && '\n' !in username)
        val id = SecretId(UUID.randomUUID().toString())
        val user = username.toByteArray(Charsets.UTF_8)
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val secret = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (encoded.hasArray()) encoded.array().fill(0)
        require(user.size <= 4096 && secret.size <= 65536)
        val plaintext = ByteBuffer.allocate(8 + user.size + secret.size).putInt(user.size).put(user).putInt(secret.size).put(secret).array()
        user.fill(0); secret.fill(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(id.value.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(File(directory, id.value))
            val output = file.startWrite()
            try {
                output.write(byteArrayOf(1, cipher.iv.size.toByte()))
                output.write(cipher.iv); output.write(ciphertext)
                file.finishWrite(output)
            } catch (failure: Exception) { file.failWrite(output); throw failure }
            id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw AppFailureException(AppError(ErrorCode.PROXY_AUTH, "network.secrets.put", false)) }
        finally { plaintext.fill(0) }
    }

    override suspend fun read(id: SecretId): ProxyCredentials? = withContext(Dispatchers.IO) {
        if (!validId(id)) return@withContext null
        var plaintext: ByteArray? = null
        try {
            val file = AtomicFile(File(directory, id.value))
            if (!file.baseFile.exists() || file.baseFile.length() !in 30..131072) return@withContext null
            val bytes = file.readFully()
            if (bytes[0] != 1.toByte() || bytes[1] != 12.toByte()) return@withContext null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: return@withContext null,
                GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
            cipher.updateAAD(id.value.toByteArray(Charsets.UTF_8))
            plaintext = cipher.doFinal(bytes, 14, bytes.size - 14)
            val data = ByteBuffer.wrap(plaintext)
            val userLength = data.int
            if (userLength !in 1..4096 || data.remaining() < userLength + 4) return@withContext null
            val user = ByteArray(userLength).also { data.get(it) }
            val passwordLength = data.int
            if (passwordLength !in 0..65536 || data.remaining() != passwordLength) { user.fill(0); return@withContext null }
            val secret = ByteArray(passwordLength).also { data.get(it) }
            val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(secret))
            val chars = CharArray(decoded.remaining()).also { decoded.get(it) }
            try { ProxyCredentials(String(user, Charsets.UTF_8), chars) }
            finally { user.fill(0); secret.fill(0); chars.fill('\u0000'); if (decoded.hasArray()) decoded.array().fill('\u0000') }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Missing/invalidated keys and tampered files cannot become credentials.
        finally { plaintext?.fill(0) }
    }

    override suspend fun remove(id: SecretId) = withContext(Dispatchers.IO) {
        if (validId(id)) AtomicFile(File(directory, id.value)).delete()
    }

    private fun validId(id: SecretId) = id.value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private fun key(create: Boolean): SecretKey? = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: if (!create) null else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
    private companion object {
        const val KEY_ALIAS = "molpy.proxy-credentials.v1"
        val keyLock = Any()
    }
}
