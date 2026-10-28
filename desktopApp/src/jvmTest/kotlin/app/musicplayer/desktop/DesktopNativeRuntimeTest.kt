package app.musicplayer.desktop

import kotlin.test.*

class DesktopNativeRuntimeTest {
    @Test
    fun hostGraphicsRuntimeIsPresent() {
        val loader = javaClass.classLoader
        val os = System.getProperty("os.name")
        val name =
            if (os.startsWith("Windows")) "skiko-windows-x64.dll" else "libskiko-linux-x64.so"
        assertNotNull(loader.getResource(name), "Native Compose runtime is missing")
    }
}
