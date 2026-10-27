package io.github.shaonialife.carptocar.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USBMUX v2 线格式单测：字段顺序与长度全部由 DiPlay 车机侧实现反推
 * （`Iap2UsbMuxHost.sendFrame()/sendTcp()/dispatchTcp()`），这里把契约钉死，
 * 避免以后「改了偏移量但真机才炸」。
 */
class UsbMuxTest {
    @Test
    fun versionFrameIsTwentyBytesWithVersionTwo() {
        val frame = UsbMux.versionFrame(sequence = 7, acknowledgement = 9)
        assertEquals(20, frame.size)
        assertEquals(UsbMux.PROTOCOL_VERSION, UsbMux.readU32(frame, 0))
        assertEquals(20, UsbMux.readU32(frame, 4))
        assertEquals(UsbMux.MAGIC, UsbMux.readU32(frame, 8))
        assertEquals(7, UsbMux.readU16(frame, 12))
        assertEquals(9, UsbMux.readU16(frame, 14))
        // 车机只认 version=2（DiPlay: 「replies with the same ... version=2 packet」）
        assertEquals(UsbMux.VERSION, UsbMux.readU32(frame, 16))
    }

    @Test
    fun setupFrameCarriesSeven() {
        val frame = UsbMux.setupFrame()
        assertEquals(17, frame.size)
        assertEquals(UsbMux.PROTOCOL_SETUP, UsbMux.readU32(frame, 0))
        assertEquals(UsbMux.SETUP_VALUE, frame[16].toInt())
    }

    @Test
    fun tcpFrameMatchesHostLayout() {
        val payload = "hello".toByteArray()
        val frame = UsbMux.tcpFrame(sourcePort = 62078, destinationPort = 40001, sequence = 1, acknowledgement = 0, flags = UsbMux.FLAG_ACK or UsbMux.FLAG_PSH, payload = payload)
        assertEquals(UsbMux.PROTOCOL_TCP, UsbMux.readU32(frame, 0))
        assertEquals(frame.size, UsbMux.readU32(frame, 4))
        val tcp = frame.copyOfRange(UsbMux.HEADER_BYTES, frame.size)
        assertEquals(62078, UsbMux.readU16(tcp, 0))
        assertEquals(40001, UsbMux.readU16(tcp, 2))
        assertEquals(1L, UsbMux.readU32Long(tcp, 4))
        assertEquals(0L, UsbMux.readU32Long(tcp, 8))
        // dataOffset 字节固定 0x50（20/4<<4），车机侧按 (byte>>4)*4 取头长
        assertEquals(0x50, tcp[12].toInt() and 0xFF)
        assertEquals(UsbMux.TCP_WINDOW, UsbMux.readU16(tcp, 14))
        assertEquals("hello", String(tcp.copyOfRange(20, tcp.size)))
    }

    @Test
    fun parseTcpRoundTrips() {
        val frame = UsbMux.tcpFrame(1024, 62078, 42L, 7L, UsbMux.FLAG_SYN, "abc".toByteArray())
        val segment = UsbMux.parseTcp(frame.copyOfRange(UsbMux.HEADER_BYTES, frame.size))
        assertEquals(1024, segment.sourcePort)
        assertEquals(62078, segment.destinationPort)
        assertEquals(42L, segment.sequence)
        assertEquals(7L, segment.acknowledgement)
        assertEquals(UsbMux.FLAG_SYN, segment.flags)
        assertEquals("abc", String(segment.payload))
    }

    @Test
    fun framerHandlesFragmentedAndCoalescedFrames() {
        val framer = UsbMux.Framer()
        val a = UsbMux.versionFrame()
        val b = UsbMux.setupFrame()
        val all = a + b
        // 先喂半帧：不能产出
        framer.append(all, 0, 10)
        assertNull(framer.take())
        // 再喂剩余：两帧都要出来（粘连场景）
        framer.append(all, 10, all.size - 10)
        val first = framer.take()
        assertEquals(UsbMux.PROTOCOL_VERSION, first?.protocol)
        val second = framer.take()
        assertEquals(UsbMux.PROTOCOL_SETUP, second?.protocol)
        assertNull(framer.take())
    }

    @Test(expected = IllegalArgumentException::class)
    fun framerRejectsImpossibleLength() {
        val framer = UsbMux.Framer()
        val bogus = ByteArray(UsbMux.HEADER_BYTES)
        UsbMux.putU32(bogus, 0, UsbMux.PROTOCOL_TCP)
        UsbMux.putU32(bogus, 4, 8) // < 头长
        framer.append(bogus)
        framer.take()
    }

    @Test
    fun framerCompactsAfterManyFrames() {
        val framer = UsbMux.Framer(64)
        repeat(50) {
            framer.append(UsbMux.setupFrame())
            assertTrue(framer.take() != null)
        }
        assertEquals(0, framer.bufferedBytes)
    }
}
