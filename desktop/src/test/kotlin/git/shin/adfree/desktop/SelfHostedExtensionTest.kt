package git.shin.adfree.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The signed half of the install.
 *
 * A CRX3 is a header of protobuf fields, a signature over a magic string plus
 * that header plus the zip, and an id derived from the key. Every one of those
 * is a byte layout, so every one of them fails silently when it is wrong: a
 * browser given a malformed CRX reports nothing in particular and installs
 * nothing, and the user sees a policy that "did not work". These tests parse
 * back what was written and verify the signature the way the browser does.
 */
class SelfHostedExtensionTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun files(): Map<String, ByteArray> = mapOf(
        "manifest.json" to
            """{"manifest_version":3,"name":"AdFree page runtime","version":"0.2.0"}"""
                .toByteArray(),
        "background.js" to "console.log('transport');".toByteArray(),
        "icons/icon-16.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
    )

    @Test
    fun theExtensionIdIsTheKeyAndDoesNotChangeBetweenReads() {
        val folder = temporary.newFolder("bundle")
        val keyFile = File(folder, "key.pem")

        val first = SelfHostedExtension.keyPair(keyFile)
        val second = SelfHostedExtension.keyPair(keyFile)

        assertEquals(
            "a second read of the same file must be the same key",
            first.public.encoded.toList(),
            second.public.encoded.toList(),
        )
        val id = SelfHostedExtension.extensionId(first)
        assertEquals(32, id.length)
        assertTrue(id, id.all { it in 'a'..'p' })
        assertEquals(id, SelfHostedExtension.extensionId(second))
    }

    @Test
    fun theCrxSignatureVerifiesOverTheBytesItCarries() {
        val key = SelfHostedExtension.keyPair(File(temporary.newFolder("keys"), "key.pem"))
        val crx = SelfHostedExtension.crx(files(), key)

        // Header as a browser reads it: magic, version, header length.
        assertEquals("Cr24", String(crx.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(3, le32(crx, 4))
        val headerLength = le32(crx, 8)
        val header = crx.copyOfRange(12, 12 + headerLength)
        val zip = crx.copyOfRange(12 + headerLength, crx.size)

        // The zip is the extension itself, unchanged.
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                names += entry.name
                stream.readBytes()
            }
        }
        assertEquals(listOf("background.js", "icons/icon-16.png", "manifest.json"), names)

        // The signed payload is `CRX3 SignedData\0`, its length, the signed
        // header, then the zip — and the signature is over exactly that.
        val signedHeader = fieldFrom(header, 10000)
        assertNotNull("the header carries signed_header_data", signedHeader)
        val signed = "CRX3 SignedData\u0000".toByteArray(Charsets.US_ASCII) +
            le32Bytes(signedHeader!!.size) + signedHeader + zip

        val proof = fieldFrom(header, 2)!!
        val publicKey = fieldFrom(proof, 1)!!
        val signature = fieldFrom(proof, 2)!!
        assertEquals(
            "the key in the header is the key that signed",
            key.public.encoded.toList(),
            publicKey.toList(),
        )
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(publicKey)))
            update(signed)
            verify(signature)
        }
        assertTrue("a browser would reject this CRX", verified)

        // The id inside the CRX is the one the policy names.
        val crxId = fieldFrom(signedHeader, 1)!!
        val expected = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).take(16)
        assertEquals(expected, crxId.toList())
        assertEquals(SelfHostedExtension.extensionId(key), toId(crxId))
    }

    @Test
    fun writingLeavesTheThreeFilesABrowserNeeds() {
        val folder = File(temporary.root, "adfree/extension")
        val id = SelfHostedExtension.write(
            files = files(),
            folder = folder,
            codebase = "https://origin.test/local/adfree-extension.crx",
        )

        val crx = File(folder, SelfHostedExtension.CRX_NAME)
        val update = File(folder, SelfHostedExtension.UPDATE_NAME)
        assertTrue(crx.isFile)
        assertTrue(update.isFile)
        assertTrue(File(folder, "key.pem").isFile)

        val manifest = update.readText()
        assertTrue(manifest, manifest.contains("<app appid='$id'>"))
        assertTrue(
            manifest,
            manifest.contains("<updatecheck codebase='https://origin.test/local/adfree-extension.crx'"),
        )
        // The version a browser compares against comes from the extension's own
        // manifest, so a rebuild is an update rather than a no-op.
        assertTrue(manifest, manifest.contains("version='0.2.0'"))
    }

    @Test
    fun thePolicyAllowsTheOriginItPointsAt() {
        val policy = SelfHostedExtension.policyJson(
            "abcdefghijklmnopabcdefghijklmnop",
            "https://origin.test/local/adfree-extension-update.xml",
        )
        assertTrue(policy, policy.contains("\"normal_installed\""))
        assertTrue(policy, policy.contains("\"update_url\""))
        // Chrome refuses a CRX from anywhere but the store unless this names the
        // host, even when the policy above already named the update URL.
        assertTrue(policy, policy.contains("\"ExtensionInstallSources\": [\"https://origin.test/local/*\"]"))
        assertFalse("a forced install is the user's to undo", policy.contains("force_installed"))
    }

    @Test
    fun onlyBrowsersThatExistGetAPolicy() {
        val configHome = temporary.newFolder("config")
        val etc = temporary.newFolder("etc")
        File(configHome, "google-chrome").mkdirs()
        File(configHome, "firefox").mkdirs()
        File(etc, "opt/chrome").mkdirs()

        val dirs = SelfHostedExtension.policyDirs(configHome, etc)

        assertEquals(listOf("/etc/opt/chrome/policies/managed"), dirs)
    }

    /**
     * The two paths and the origin are a contract with the core: a browser that
     * is handed an update URL the proxy does not answer sees a 404 at start and
     * installs nothing. Nothing in either language's type system holds the pair
     * together, so this reads the other side.
     */
    @Test
    fun thePathsMatchTheProxyThatServesThem() {
        val source = File("../rust/src/mitm/proxy.rs")
        if (!source.isFile) return // a jar without the repository next to it
        val text = source.readText()

        assertTrue(
            "the proxy does not serve ${SelfHostedExtension.CRX_PATH}",
            text.contains("pub const EXT_CRX_PATH: &str = \"${SelfHostedExtension.CRX_PATH}\""),
        )
        assertTrue(
            "the proxy does not serve ${SelfHostedExtension.UPDATE_PATH}",
            text.contains("pub const EXT_UPDATE_PATH: &str = \"${SelfHostedExtension.UPDATE_PATH}\""),
        )
        assertTrue(
            "the origin is not the one the proxy answers",
            text.contains("pub const LOCAL_ORIGIN: &str = \"${SelfHostedExtension.LOCAL_ORIGIN}\""),
        )
    }

    private fun toId(bytes: ByteArray): String = bytes.take(16).joinToString("") {
        val value = it.toInt() and 0xff
        "${('a' + (value shr 4))}${('a' + (value and 0x0f))}"
    }

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)

    private fun le32Bytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    /** The first length-delimited field with this number, or null. */
    private fun fieldFrom(bytes: ByteArray, number: Int): ByteArray? {
        var i = 0
        while (i < bytes.size) {
            var key = 0L
            var shift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xff
                key = key or ((b.toLong() and 0x7f) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            val fieldNumber = (key ushr 3).toInt()
            val wireType = (key and 0x7).toInt()
            if (wireType != 2) return null
            var length = 0
            var lengthShift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xff
                length = length or ((b and 0x7f) shl lengthShift)
                lengthShift += 7
                if (b and 0x80 == 0) break
            }
            val payload = bytes.copyOfRange(i, i + length)
            if (fieldNumber == number) return payload
            i += length
        }
        return null
    }
}
