package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.web.Crypto.b64url
import com.kayloh20.kyliemail.web.Crypto.fromB64url
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import kotlinx.serialization.Serializable

// What the browser's navigator.credentials.create() and get() need, with binary values in base64url.
// The page turns them into ArrayBuffers.

@Serializable data class RelyingParty(val id: String, val name: String)
@Serializable data class UserEntity(val id: String, val name: String, val displayName: String)
@Serializable data class CredentialParam(val type: String = "public-key", val alg: Long)
@Serializable data class CredentialDescriptor(val id: String, val type: String = "public-key")
@Serializable data class AuthenticatorSelection(
    val residentKey: String = "required",
    val requireResidentKey: Boolean = true,
    val userVerification: String = "required",
)
@Serializable data class PrfValues(val first: String)
@Serializable data class PrfCreateInput(val eval: PrfValues)
@Serializable data class CreateExtensions(val prf: PrfCreateInput)
@Serializable data class PrfGetInput(val evalByCredential: Map<String, PrfValues>)
@Serializable data class GetExtensions(val prf: PrfGetInput)

@Serializable data class CreationOptions(
    val challenge: String,
    val rp: RelyingParty,
    val user: UserEntity,
    val pubKeyCredParams: List<CredentialParam>,
    val timeout: Long,
    val attestation: String = "none",
    val authenticatorSelection: AuthenticatorSelection = AuthenticatorSelection(),
    val excludeCredentials: List<CredentialDescriptor>,
    val extensions: CreateExtensions,
)

@Serializable data class RequestOptions(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<CredentialDescriptor>,
    val userVerification: String = "required",
    val timeout: Long,
    val extensions: GetExtensions,
)

// What the page sends back.

@Serializable data class AttestationResponse(val clientDataJSON: String, val attestationObject: String)
@Serializable data class RegistrationCredential(val id: String, val response: AttestationResponse)
@Serializable data class AssertionResponse(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null,
)
@Serializable data class AssertionCredential(val id: String, val response: AssertionResponse)

class PasskeyException(message: String) : Exception(message)

/** Registers and checks passkeys for [config]'s address, using webauthn4j for the WebAuthn rules. */
class Passkeys(private val config: WebConfig) {
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val credentialDataConverter = AttestedCredentialDataConverter(ObjectConverter())
    private val algorithms = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256)

    fun creationOptions(challenge: ByteArray, userHandle: String, email: String, exclude: List<String>, prfSalt: String) = CreationOptions(
        challenge = b64url(challenge),
        rp = RelyingParty(config.host, "Kylie Mail"),
        user = UserEntity(userHandle, email, email),
        pubKeyCredParams = algorithms.map { CredentialParam(alg = it.value) },
        timeout = TIMEOUT_MS,
        excludeCredentials = exclude.map { CredentialDescriptor(it) },
        extensions = CreateExtensions(PrfCreateInput(PrfValues(prfSalt))),
    )

    fun requestOptions(challenge: ByteArray, passkeys: List<StoredPasskey>) = RequestOptions(
        challenge = b64url(challenge),
        rpId = config.host,
        allowCredentials = passkeys.map { CredentialDescriptor(it.id) },
        timeout = TIMEOUT_MS,
        extensions = GetExtensions(PrfGetInput(passkeys.associate { it.id to PrfValues(it.prfSalt) })),
    )

    /** Checks a new passkey made in answer to [challenge] and returns what to keep about it. */
    fun verifyRegistration(credential: RegistrationCredential, challenge: ByteArray, name: String, prfSalt: String): NewPasskey {
        val data = try {
            manager.verify(
                RegistrationRequest(decode(credential.response.attestationObject), decode(credential.response.clientDataJSON)),
                RegistrationParameters(
                    ServerProperty(Origin(config.origin), config.host, DefaultChallenge(challenge)),
                    algorithms.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) },
                    true,
                    true,
                ),
            )
        } catch (e: PasskeyException) {
            throw e
        } catch (e: Exception) {
            throw PasskeyException("The passkey couldn't be checked")
        }
        val authenticatorData = data.attestationObject?.authenticatorData ?: throw PasskeyException("The passkey sent no data")
        val credentialData = authenticatorData.attestedCredentialData ?: throw PasskeyException("The passkey sent no key")
        val id = b64url(credentialData.credentialId)
        if (id != credential.id.trimEnd('=')) throw PasskeyException("The passkey's ID doesn't match")
        return NewPasskey(
            id = id,
            name = name,
            credentialData = b64url(credentialDataConverter.convert(credentialData)),
            signCount = authenticatorData.signCount,
            backupEligible = authenticatorData.isFlagBE,
            backupState = authenticatorData.isFlagBS,
            prfSalt = prfSalt,
        )
    }

    /** Checks a sign-in with [stored] in answer to [challenge] and returns its new signature counter. */
    fun verifyAssertion(credential: AssertionCredential, challenge: ByteArray, stored: StoredPasskey): Long {
        val record = CredentialRecordImpl(
            NoneAttestationStatement(),
            true,
            stored.backupEligible,
            stored.backupState,
            stored.signCount,
            credentialDataConverter.convert(fromB64url(stored.credentialData)),
            null,
            null,
            null,
            null,
        )
        val data = try {
            manager.verify(
                AuthenticationRequest(
                    fromB64url(stored.id),
                    credential.response.userHandle?.takeIf { it.isNotEmpty() }?.let(::decode),
                    decode(credential.response.authenticatorData),
                    decode(credential.response.clientDataJSON),
                    decode(credential.response.signature),
                ),
                AuthenticationParameters(
                    ServerProperty(Origin(config.origin), config.host, DefaultChallenge(challenge)),
                    record,
                    listOf(fromB64url(stored.id)),
                    true,
                    true,
                ),
            )
        } catch (e: Exception) {
            throw PasskeyException("The passkey couldn't be checked")
        }
        return data.authenticatorData?.signCount ?: stored.signCount
    }

    private fun decode(value: String): ByteArray = try {
        fromB64url(value)
    } catch (e: IllegalArgumentException) {
        throw PasskeyException("The passkey sent something unreadable")
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
    }
}

