package app.musicplayer.desktop.playback

import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class MpvConnectionTest {
    @Test
    fun correlatesResponsesAndDoesNotLeakProviderErrors() = runBlocking {
        val input = PipedInputStream()
        val serverOutput = PipedOutputStream(input)
        val serverInput = PipedInputStream()
        val output = PipedOutputStream(serverInput)
        val connection = MpvConnection(input, output, this, timeoutMs = 1000)
        val response = async {
            connection.command(listOf(JsonPrimitive("get_property"), JsonPrimitive("duration")))
        }
        withContext(Dispatchers.IO) {
            val request =
                Json.parseToJsonElement(serverInput.bufferedReader().readLine()).jsonObject
            val id = request.getValue("request_id")
            serverOutput.write(
                "{\"event\":\"start-file\"}\n{\"request_id\":$id,\"error\":\"success\",\"data\":120}\n"
                    .toByteArray()
            )
            serverOutput.flush()
        }
        assertEquals(120, response.await().jsonPrimitive.int)
        connection.close()
        serverOutput.close()
        serverInput.close()
    }

    @Test
    fun timeoutClosesConnectionAndRedactsArguments() = runBlocking {
        val input = PipedInputStream()
        val server = PipedOutputStream(input)
        val connection = MpvConnection(input, java.io.ByteArrayOutputStream(), this, timeoutMs = 50)
        val error =
            assertFailsWith<MpvException> {
                connection.command(listOf(JsonPrimitive("secret-url")))
            }
        assertFalse(error.toString().contains("secret-url"))
        assertFailsWith<MpvException> { connection.command(listOf(JsonPrimitive("play"))) }
        connection.close()
        server.close()
    }
}
