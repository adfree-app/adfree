package git.shin.adfree.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The extension, packaged the way a browser installs it by itself.
 *
 * A browser takes an extension from three places: its store, a folder the user
 * picks, or a policy. The first is not ours to use; the second is the click
 * this type exists to remove; the third works — the browser is handed an update
 * manifest, the manifest names a CRX, and the CRX carries a signature over its
 * own bytes and the key that signed them. Chrome then installs it like any
 * other extension, at start, with no folder and no developer mode.
 *
 * The key is generated here, per machine, and never leaves the data directory.
 * That is deliberate: a key shipped in a package would give everyone the same
 * extension id, and an id everyone shares cannot be revoked on one machine. It
 * also means the CRX must be rebuilt per machine, which is cheap — it is a few
 * kilobytes of transport.
 */
object SelfHostedExtension {
    /** The file names the proxy serves. The URL's last segment is the file name. */
    const val CRX_NAME = "adfree-extension.crx"
    const val UPDATE_NAME = "adfree-extension-update.xml"

    /** Where those files live, under the app's data directory. */
    const val FOLDER = "extension"

    const val KEY_NAME = "key.pem"

    /**
     * The origin the proxy answers on, and the two paths it serves the bundle
     * from. Both have to match `rust/src/mitm/proxy.rs` exactly: a browser that
     * is handed an update URL the proxy does not answer sees a 404 at start and
     * simply installs nothing, which is indistinguishable from a policy that was
     * never read.
     */
    const val LOCAL_ORIGIN = "https://yuuki-kagura.github.io/local"
    const val CRX_PATH = "/adfree-extension.crx"
    const val UPDATE_PATH = "/adfree-extension-update.xml"

    /** Where a browser keeps the policy files it reads at start, per brand. */
    private val POLICY_DIRS = mapOf(
        "google-chrome" to "/etc/opt/chrome/policies/managed",
        "google-chrome-beta" to "/etc/opt/chrome/policies/managed",
        "google-chrome-unstable" to "/etc/opt/chrome/policies/managed",
        "chromium" to "/etc/chromium/policies/managed",
        "BraveSoftware/Brave-Browser" to "/etc/brave/policies/managed",
        "microsoft-edge" to "/etc/opt/edge/policies/managed",
        "microsoft-edge-beta" to "/etc/opt/edge/policies/managed",
        "vivaldi" to "/etc/opt/vivaldi/policies/managed",
        "opera" to "/etc/opera/policies/managed",
    )

    /**
     * The policy directories of the browsers actually installed here.
     *
     * A brand is written only when its `/etc` side exists: creating
     * `/etc/opt/vivaldi` on a machine that never had Vivaldi is clutter, and a
     * policy nobody reads looks like a successful install.
     */
    fun policyDirs(
        configHome: File = NativeMessagingHosts.configHome(),
        etc: File = File("/etc"),
    ): List<String> {
        val brands = NativeMessagingHosts.installedBrowsers(configHome)
            .map { it.relativeTo(configHome).path }
        return brands.mapNotNull { brand -> POLICY_DIRS[brand] }
            .distinct()
            .filter { dir -> brandRootExists(dir, etc) }
    }

    /**
     * The browsers that already have this machine's build, by name.
     *
     * A policy can be written and the browser still not restarted, so neither
     * file on disk answers "did it install?" — each browser's own profile does,
     * and it is the only place the answer is true. The id is per machine, so a
     * match is this build and not somebody else's extension.
     */
    fun installedIn(
        id: String,
        configHome: File = NativeMessagingHosts.configHome(),
    ): List<String> = NativeMessagingHosts.installedBrowsers(configHome).mapNotNull { root ->
        val present = root.walkTopDown()
            .maxDepth(3)
            .any { it.isDirectory && it.name == "Extensions" && File(it, id).isDirectory }
        if (present) BRAND_NAMES[root.relativeTo(configHome).path] ?: root.name else null
    }

    /** Whether a policy naming this build is already where a browser reads it. */
    fun policyWritten(dirs: List<String>, id: String): Boolean = dirs.any { dir ->
        val policy = File(dir, POLICY_NAME)
        policy.isFile && runCatching { policy.readText().contains(id) }.getOrDefault(false)
    }

    /** What each brand is called in the UI. */
    private val BRAND_NAMES = mapOf(
        "google-chrome" to "Chrome",
        "google-chrome-beta" to "Chrome Beta",
        "google-chrome-unstable" to "Chrome Canary",
        "chromium" to "Chromium",
        "BraveSoftware/Brave-Browser" to "Brave",
        "microsoft-edge" to "Edge",
        "microsoft-edge-beta" to "Edge Beta",
        "vivaldi" to "Vivaldi",
        "opera" to "Opera",
        "yandex-browser" to "Yandex",
    )

    /** The policy file the browser reads, per directory. */
    const val POLICY_NAME = "adfree-extension.json"

    /**
     * Whether the browser's own directory under `/etc` is there — `/etc/opt/chrome`
     * for Chrome, `/etc/chromium` for Chromium. Checked on the part before
     * `policies`, which the installer may have to create.
     */
    private fun brandRootExists(policyDir: String, etc: File): Boolean {
        val parts = policyDir.removePrefix("/etc/").removeSuffix("/policies/managed")
        return File(etc, parts).isDirectory
    }

    /** The CRX container: `Cr24`, format 3, big-endian lengths after the magic. */
    private const val MAGIC = "Cr24"
    private const val CRX3 = 3

    /** The bytes the signature covers, and the protobuf's own signature label. */
    private const val SIGNED_DATA_MAGIC = "CRX3 SignedData\u0000"

    /**
     * Writes everything a machine needs to self-install: the key, the signed
     * CRX, and the update manifest that names it.
     *
     * [files] are the extension's own files by relative path — whatever the
     * bundle in the jar holds. Returns the extension id, which the policy needs
     * and which is why the key has to exist before the CRX does.
     */
    fun write(files: Map<String, ByteArray>, folder: File, codebase: String): String {
        folder.mkdirs()
        val key = keyPair(File(folder, KEY_NAME))
        val id = extensionId(key)
        val version = versionOf(files["manifest.json"])
        File(folder, CRX_NAME).writeBytes(crx(files, key))
        File(folder, UPDATE_NAME).writeText(updateManifest(id, version, codebase))
        return id
    }

    /**
     * The key this machine signs with, minted on first use.
     *
     * Only the private half is kept; the public half is derived, and the
     * extension id with it. Losing this file is not a failure — the next write
     * mints a new key and a new id — but it does mean the policy has to be
     * written again, because the old id no longer names anything.
     */
    /**
     * This machine's id, or null when no key has been minted yet — the query a
     * card asks before anything is installed, which must not mint one as a side
     * effect. [keyPair] mints; this only reads.
     */
    fun existingExtensionId(file: File): String? =
        if (file.isFile) runCatching { extensionId(readKeyPair(file)) }.getOrNull() else null

    fun keyPair(file: File): KeyPair {
        if (file.isFile) {
            runCatching { readKeyPair(file) }.getOrNull()?.let { return it }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        file.parentFile?.mkdirs()
        file.writeText(
            "-----BEGIN PRIVATE KEY-----\n" +
                wrap(Base64.getEncoder().encodeToString(pair.private.encoded)) +
                "\n-----END PRIVATE KEY-----\n",
        )
        // 0600 by another name: the key signs what every browser on this machine
        // will run, and the user is the only one who needs it.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return pair
    }

    private fun readKeyPair(file: File): KeyPair {
        val pem = file.readText()
        val body = pem.substringAfter("-----BEGIN PRIVATE KEY-----")
            .substringBefore("-----END PRIVATE KEY-----")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(body)
        val spec = java.security.spec.PKCS8EncodedKeySpec(der)
        val private = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec)
        // The public half is the modulus and exponent inside the private key:
        // RSAPrivateCrtKey carries both, and the id must not change when only
        // the file was read back.
        val crt = private as java.security.interfaces.RSAPrivateCrtKey
        val publicSpec = java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        val public = java.security.KeyFactory.getInstance("RSA").generatePublic(publicSpec)
        return KeyPair(public, private)
    }

    /**
     * The id a browser will show: the first 16 bytes of the SHA-256 of the
     * public key, each nibble mapped onto `a`–`p`.
     */
    fun extensionId(key: KeyPair): String = idFromPublicKey(key.public.encoded)

    internal fun idFromPublicKey(publicKeyDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
        return digest.take(16).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${('a' + (value shr 4))}${('a' + (value and 0x0f))}"
        }
    }

    /** The CRX3 container: magic, header, then the zip unchanged. */
    fun crx(files: Map<String, ByteArray>, key: KeyPair): ByteArray {
        val zip = zip(files)
        val id = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).take(16).toByteArray()
        // `SignedData { bytes crx_id = 1; }` — the proto field is 1, wire type 2.
        val signedHeader = field(1, id)
        val signed = ByteArrayOutputStream().apply {
            write(SIGNED_DATA_MAGIC.toByteArray(Charsets.US_ASCII))
            write(uint32(signedHeader.size))
            write(signedHeader)
            write(zip)
        }.toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private as PrivateKey)
            update(signed)
            sign()
        }
        // `CrxFileHeader { AsymmetricKeyProof sha256_with_rsa = 2; bytes
        // signed_header_data = 10000; }`, and the proof is
        // `{ bytes public_key = 1; bytes signature = 2; }`.
        val proof = field(1, key.public.encoded) + field(2, signature)
        val header = field(2, proof) + field(10000, signedHeader)
        return ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(uint32(CRX3))
            write(uint32(header.size))
            write(header)
            write(zip)
        }.toByteArray()
    }

    /**
     * What the browser's update service reads: one app, its id, and where the
     * bytes are. `no-store` is the app's job; here it only has to be truthful.
     */
    fun updateManifest(id: String, version: String, codebase: String): String =
        """
        <?xml version='1.0' encoding='UTF-8'?>
        <gupdate xmlns='http://www.google.com/update2/response' protocol='2.0'>
          <app appid='$id'>
            <updatecheck codebase='$codebase' version='$version'/>
          </app>
        </gupdate>
        """.trimIndent() + "\n"

    /**
     * The managed policy that makes the install automatic.
     *
     * `normal_installed` rather than `force_installed` on purpose: the
     * extension is the user's to remove. `ExtensionInstallSources` is what
     * lets a CRX come from somewhere other than the store — without it Chrome
     * refuses the download even though the policy named it.
     */
    fun policyJson(id: String, updateUrl: String): String = """
        {
          "ExtensionSettings": {
            "$id": {
              "installation_mode": "normal_installed",
              "update_url": "$updateUrl",
              "toolbar_pin": "force_pinned"
            }
          },
          "ExtensionInstallSources": ["${updateUrl.substringBeforeLast('/')}/*"]
        }
    """.trimIndent() + "\n"

    private fun versionOf(manifest: ByteArray?): String {
        val text = manifest?.toString(Charsets.UTF_8) ?: return "0.0.0"
        return Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "0.0.0"
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in files.keys.sorted()) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(files.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A protobuf field of wire type 2 (length-delimited). */
    private fun field(number: Int, payload: ByteArray): ByteArray =
        varint((number.toLong() shl 3) or 2L) + varint(payload.size.toLong()) + payload

    private fun varint(value: Long): ByteArray {
        var rest = value
        val out = ByteArrayOutputStream()
        while (rest and 0x7fL.inv() != 0L) {
            out.write(((rest and 0x7fL) or 0x80L).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private fun wrap(base64: String, width: Int = 64): String =
        base64.chunked(width).joinToString("\n")
}
