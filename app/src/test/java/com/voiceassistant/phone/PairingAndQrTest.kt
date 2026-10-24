package com.voiceassistant.phone

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

class PairingTextTest {
    private val v2 = """{"v":2,"host":"100.1.2.3","port":47830,"code":"ABCDEFGHJK","name":"MY-PC"}"""

    @Test
    fun theQrTextGivesTheAddressAndTheOneTimeCode() {
        val input = PairingText.parse(v2) as PairingInput.Code
        assertEquals(PairingInput.Code("100.1.2.3", 47830, "ABCDEFGHJK", "MY-PC"), input)
    }

    @Test
    fun theOldFormatWithAPermanentTokenStillWorks() {
        val old = """{"v":1,"host":"100.1.2.3","port":47830,"token":"secret-token","id":"abcd1234"}"""
        val input = PairingText.parse(old) as PairingInput.Token
        assertEquals(Pairing("100.1.2.3", 47830, "secret-token", "My PC"), input.pairing)
    }

    @Test
    fun aCodeTypedByHandWithTheAddressIsAccepted() {
        assertEquals(
            PairingInput.Code("100.1.2.3", 47830, "ABCDEFGHJK", "My PC"),
            PairingText.parse("100.1.2.3:47830 abcd-efgh-jk"),
        )
        assertEquals(PairingInput.Code("100.1.2.3", 47830, "ABCDEFGHJK", "My PC"), PairingText.parse("  100.1.2.3  ABCD EFGH JK "))
        assertEquals(PairingInput.Code("michaelspc.tail1234.ts.net", 5000, "ABCDEFGHJK", "My PC"), PairingText.parse("michaelspc.tail1234.ts.net:5000 ABCDEFGHJK"))
    }

    @Test
    fun anythingElseIsInvalid() {
        for (bad in listOf("", "hello", "ABCD-EFGH-JK", "100.1.2.3 SHORT", "100.1.2.3 ABCDEFGHJ0", """{"v":2}""", """{"host":"h","port":47830}""",
            """{"host":"100.1.2.3","port":47830,"code":"short"}""", """{"host":"100.1.2.3","port":80,"code":"ABCDEFGHJK"}""", "{not json")) {
            assertEquals("should be invalid: $bad", PairingInput.Invalid, PairingText.parse(bad))
        }
    }

    @Test
    fun codesAreShownInGroups() {
        assertEquals("ABCD-EFGH-JK", PairingText.format("ABCDEFGHJK"))
        assertEquals("ABCDEFGHJK", PairingText.normalize("abcd-efgh jk"))
    }
}

class ConnectionDoctorTest {
    private fun d(
        paired: Boolean = true, connected: Boolean = false, installed: Boolean? = true, vpn: Boolean? = true, knows: Boolean = true, reach: Boolean? = true,
    ) = ConnectionDoctor.diagnose(Facts(paired, connected, installed, vpn, knows, reach))

    @Test
    fun notPairedComesFirst() = assertEquals(Fix.PairAgain, d(paired = false, installed = false).fix)

    @Test
    fun connectedMeansEverythingWorks() {
        val ok = d(connected = true, vpn = false) // even a stale VPN reading does not matter when it is actually connected
        assertTrue(ok.ok)
        assertEquals(Fix.None, ok.fix)
    }

    @Test
    fun theProblemsAreFoundInTheOrderAPersonWouldCheckThem() {
        assertEquals(Fix.InstallTailscale, d(installed = false, vpn = false, reach = false).fix)
        assertEquals(Fix.OpenTailscale, d(vpn = false, reach = false).fix)
        assertEquals(Fix.PairAgain, d(knows = false, reach = false).fix)
        assertEquals(Fix.WakePc, d(reach = false).fix)
    }

    @Test
    fun unknownReadingsDoNotCauseFalseAccusations() {
        val vague = d(installed = null, vpn = null, reach = null)
        assertEquals(Fix.None, vague.fix)
        assertTrue(!vague.ok && vague.message.contains("nothing obvious"))
    }

    @Test
    fun everyMessageIsPlainAndNamesWhatToDo() {
        for (f in listOf(d(installed = false), d(vpn = false), d(reach = false), d(knows = false))) assertTrue(f.message.length > 25)
        assertTrue(ConnectionDoctor.refusal("bad_pair_code").contains("Show a new code"))
        assertTrue(ConnectionDoctor.refusal("not_paired").contains("Pair it again"))
    }
}

class QrDecodeTest {
    private val payload = """{"v":2,"host":"100.1.2.3","port":47830,"code":"ABCDEFGHJK","name":"MY-PC"}"""

    private fun luminanceOf(text: String, size: Int = 300): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bytes = ByteArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0 else 255.toByte() }
        return Triple(bytes, size, size)
    }

    @Test
    fun readsAGeneratedCode() {
        val (bytes, w, h) = luminanceOf(payload)
        assertEquals(payload, QrDecode.fromLuminance(bytes, w, w, h))
    }

    @Test
    fun readsACodeInAFrameWithPaddedRows() {
        val (bytes, w, h) = luminanceOf(payload)
        val stride = w + 20 // camera rows are often wider than the picture
        val padded = ByteArray(stride * h) { 128.toByte() }
        for (y in 0 until h) System.arraycopy(bytes, y * w, padded, y * stride, w)
        assertEquals(payload, QrDecode.fromLuminance(padded, stride, w, h))
    }

    @Test
    fun readsACodeTurnedSideways() {
        val (bytes, w, h) = luminanceOf(payload)
        val turned = ByteArray(w * h) { i -> bytes[(i % w) * w + (w - 1 - i / w)] } // 90 degrees: phones often hold the camera that way
        assertEquals(payload, QrDecode.fromLuminance(turned, w, w, h))
    }

    @Test
    fun aFrameWithNoCodeGivesNothing() {
        assertNull(QrDecode.fromLuminance(ByteArray(200 * 200) { 200.toByte() }, 200, 200, 200))
    }

    @Test
    fun readsTheQrCodeTheRealPcDrawsOnItsScreen() {
        // pc-qr.png was produced by the assistant's own QR widget (voice_assistant/phone_page.py)
        val image = ImageIO.read(javaClass.classLoader.getResource("pc-qr.png"))
        val bytes = ByteArray(image.width * image.height) { i ->
            val rgb = image.getRGB(i % image.width, i / image.width)
            (((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3).toByte()
        }
        val expected = javaClass.classLoader.getResource("pc-qr.txt")!!.readText()
        val text = QrDecode.fromLuminance(bytes, image.width, image.width, image.height)
        assertEquals(expected, text)
        assertTrue(PairingText.parse(text!!) is PairingInput.Code)
    }
}
