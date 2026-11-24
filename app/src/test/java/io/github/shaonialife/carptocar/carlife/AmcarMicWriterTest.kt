package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AmcarMicWriterTest {
    @Test
    fun onlyCurrentGenerationIsWritten() {
        val out = ByteArrayOutputStream()
        val writer =
            AmcarMicWriter(
                output = out,
                closeSocket = {},
                failed = {},
                now = { 0L },
            )
        try {
            assertFalse(writer.offer(ByteArray(64)))
            writer.record(true)
            assertTrue(writer.offer(ByteArray(64)))
            val deadline = System.currentTimeMillis() + 2_000
            while (out.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(out.size() >= 12 + 64)
            writer.record(false)
            assertFalse(writer.offer(ByteArray(64)))
        } finally {
            writer.close()
        }
    }
}
