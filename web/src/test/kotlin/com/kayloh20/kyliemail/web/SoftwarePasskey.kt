package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.web.Crypto.b64url
import com.kayloh20.kyliemail.web.Crypto.fromB64url
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A passkey in software, answering the way a phone or laptop would: ES256 keys, "none" attestation,
 * user verified, and a PRF that hashes the server's salt with a secret only this passkey knows.
 */
class SoftwarePasskey(private val origin: String, private val rpId: String) {
    val id: ByteArray = Crypto.randomBytes(16)
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val prfSecret = Crypto.randomBytes(32)
    private var counter = 0

    fun prf(salt: String): String = b64url(Crypto.hkdf(prfSecret, fromB64url(salt), "test prf"))

    fun create(options: CreationOptions, challenge: String = options.challenge): RegistrationCredential {
        val clientData = clientData("webauthn.create", challenge)
        val authData = authenticatorData(attested = true)
        val attestation = Cbor.map(
            "fmt" to Cbor.text("none"),
            "attStmt" to Cbor.map(),
            "authData" to Cbor.bytes(authData),
        )
        return RegistrationCredential(b64url(id), AttestationResponse(b64url(clientData), b64url(attestation)))
    }

    fun get(options: RequestOptions, challenge: String = options.challenge, userHandle: String? = null): AssertionCredential {
        val clientData = clientData("webauthn.get", challenge)
        val authData = authenticatorData(attested = false)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(authData + Crypto.sha256(clientData))
            sign()
        }
        return AssertionCredential(b64url(id), AssertionResponse(b64url(clientData), b64url(authData), b64url(signature), userHandle))
    }

    private fun clientData(type: String, challenge: String) =
        """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun authenticatorData(attested: Boolean): ByteArray {
        counter++
        val out = ByteArrayOutputStream()
        out.write(Crypto.sha256(rpId.toByteArray()))
        // User present, user verified, backup eligible and backed up, and (when registering) key attached.
        out.write(0x01 or 0x04 or 0x08 or 0x10 or (if (attested) 0x40 else 0))
        out.write(ByteBuffer.allocate(4).putInt(counter).array())
        if (attested) {
            out.write(ByteArray(16))
            out.write(ByteBuffer.allocate(2).putShort(id.size.toShort()).array())
            out.write(id)
            val point = (keys.public as ECPublicKey).w
            out.write(
                Cbor.intMap(
                    1L to Cbor.int(2),
                    3L to Cbor.int(-7),
                    -1L to Cbor.int(1),
                    -2L to Cbor.bytes(unsigned32(point.affineX)),
                    -3L to Cbor.bytes(unsigned32(point.affineY)),
                ),
            )
        }
        return out.toByteArray()
    }

    private fun unsigned32(n: BigInteger): ByteArray {
        val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }
}

/** Just enough CBOR (RFC 8949) to build attestation objects and COSE keys. */
object Cbor {
    private fun head(major: Int, value: Long): ByteArray {
        val m = major shl 5
        return when {
            value < 24 -> byteArrayOf((m or value.toInt()).toByte())
            value < 256 -> byteArrayOf((m or 24).toByte(), value.toByte())
            value < 65536 -> byteArrayOf((m or 25).toByte(), (value shr 8).toByte(), value.toByte())
            else -> byteArrayOf((m or 26).toByte()) + ByteBuffer.allocate(4).putInt(value.toInt()).array()
        }
    }

    fun int(value: Long): ByteArray = if (value >= 0) head(0, value) else head(1, -1 - value)
    fun int(value: Int) = int(value.toLong())
    fun bytes(value: ByteArray) = head(2, value.size.toLong()) + value
    fun text(value: String) = value.toByteArray().let { head(3, it.size.toLong()) + it }
    fun map(vararg entries: Pair<String, ByteArray>): ByteArray =
        entries.fold(head(5, entries.size.toLong())) { acc, (k, v) -> acc + text(k) + v }
    fun intMap(vararg entries: Pair<Long, ByteArray>): ByteArray =
        entries.fold(head(5, entries.size.toLong())) { acc, (k, v) -> acc + int(k) + v }
}
