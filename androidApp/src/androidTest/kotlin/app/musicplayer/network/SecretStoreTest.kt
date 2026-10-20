package app.musicplayer.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    @Test fun credentialsSurviveRecreationOnlyAsKeystoreCiphertext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidSecretStore(context)
        val input = "test-password".toCharArray()
        val id = store.put("test-user", input)
        try {
            input.fill('x')
            AndroidSecretStore(context).read(id)!!.use {
                assertEquals("test-user", it.username)
                assertArrayEquals("test-password".toCharArray(), it.password())
                assertFalse(it.toString().contains("test-user"))
            }
            val files = java.io.File(context.noBackupFilesDir, "proxy-secrets").listFiles()!!.map { it.readBytes().toString(Charsets.ISO_8859_1) }
            assertFalse(files.any { it.contains("test-password") || it.contains("test-user") })
        } finally { store.remove(id) }
        assertNull(store.read(id))
    }
    @Test fun corruptCiphertextDoesNotProduceUsableCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidSecretStore(context); val id = store.put("user", "password".toCharArray())
        try {
            val file = java.io.File(context.noBackupFilesDir, "proxy-secrets/${id.value}")
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertNull(store.read(id))
        } finally { store.remove(id) }
    }
}
