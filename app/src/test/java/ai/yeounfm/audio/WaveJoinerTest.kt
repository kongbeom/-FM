package ai.yeounfm.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WaveJoinerTest {
    private fun wave(file: File, payload: ByteArray, rate: Int = 16000) {
        val buffer = ByteBuffer.allocate(44 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + payload.size).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(payload.size).put(payload)
        file.writeBytes(buffer.array())
    }
    @Test fun joinsPcmPayloadAndWritesConsistentRiffSize() {
        val dir = Files.createTempDirectory("yeounfm-wave").toFile()
        try {
            val a = File(dir, "a.wav"); val b = File(dir, "b.wav"); val out = File(dir, "joined.wav")
            wave(a, byteArrayOf(1, 2)); wave(b, byteArrayOf(3, 4))
            WaveJoiner.join(listOf(a, b), out)
            val bytes = out.readBytes()
            assertEquals(48, bytes.size)
            assertEquals(40, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(44, 48))
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
    @Test(expected = IllegalArgumentException::class) fun refusesDifferentVoiceFormats() {
        val dir = Files.createTempDirectory("yeounfm-wave").toFile()
        try {
            val a = File(dir, "a.wav"); val b = File(dir, "b.wav")
            wave(a, byteArrayOf(1, 2)); wave(b, byteArrayOf(3, 4), 22050)
            WaveJoiner.join(listOf(a, b), File(dir, "out.wav"))
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
    @Test(expected = IllegalArgumentException::class) fun refusesTruncatedAudio() {
        val dir = Files.createTempDirectory("yeounfm-wave").toFile()
        try {
            val a = File(dir, "a.wav"); wave(a, byteArrayOf(1, 2))
            a.writeBytes(a.readBytes().copyOf(45))
            WaveJoiner.join(listOf(a), File(dir, "out.wav"))
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
}
