package com.kayloh20.kyliemail.data.relay

import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.AccountSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRelayTest {
    private var now = 1_000_000L
    private var saved: RelayConfig? = null
    private val store = object : RelayConfigStore {
        override fun load() = saved
        override fun save(config: RelayConfig?) { saved = config }
    }
    private val accounts = object : AccountSource {
        override val account: StateFlow<Account?> = MutableStateFlow(Account("kylie@icloud.com", "kylie@icloud.com", "abcd-efgh"))
    }
    private var tokenDeleted = false
    private val tokens = object : PushTokens {
        override suspend fun token() = "phone-token"
        override suspend fun delete() { tokenDeleted = true }
    }

    /** A pretend relay: what it answers, and what it was asked. */
    private inner class FakeApi : RelayApi {
        var registerReply: RelayReply<RelayCredentials> = RelayReply.Ok(RelayCredentials("acct", "key-1"))
        var checkInReply: RelayReply<String> = RelayReply.Ok("watching")
        val calls = mutableListOf<String>()
        override suspend fun register(server: String, setupCode: String, username: String, password: String, token: String, deviceName: String): RelayReply<RelayCredentials> {
            calls += "register $server $setupCode $username $token"
            return registerReply
        }
        override suspend fun checkIn(server: String, credentials: RelayCredentials, token: String, deviceName: String): RelayReply<String> {
            calls += "checkin ${credentials.key}"
            return checkInReply
        }
        override suspend fun unregister(server: String, credentials: RelayCredentials, token: String): RelayReply<Unit> {
            calls += "unregister ${credentials.key} $token"
            return RelayReply.Ok(Unit)
        }
    }

    private val api = FakeApi()
    private var modeChanges = 0
    private val relay = PushRelay(store, api, accounts, tokens, "Pixel", clock = { now }, onModeChanged = { modeChanges++ })

    @Test
    fun connectingSignsUpAndTakesOverFromThePhone() = runBlocking {
        assertFalse(relay.handlesInstantMail())
        assertEquals(ConnectResult.Connected, relay.connect("mail-push.example.org", " the-code "))
        assertEquals("register https://mail-push.example.org the-code kylie@icloud.com phone-token", api.calls.single())
        assertTrue(relay.handlesInstantMail())
        assertTrue(relay.status.value is RelayStatus.Connected)
        assertTrue("the phone's own watcher is told to stop", modeChanges > 0)
    }

    @Test
    fun aBadAddressOrCodeSavesNothing() = runBlocking {
        assertEquals(ConnectResult.BadAddress, relay.connect("not a server", "code"))
        api.registerReply = RelayReply.BadSetupCode
        assertEquals(ConnectResult.BadSetupCode, relay.connect("mail-push.example.org", "nope"))
        assertNull(saved)
        assertEquals(RelayStatus.Off, relay.status.value)
    }

    @Test
    fun ifTheRelayGoesQuietThePhoneTakesBackOver() = runBlocking {
        relay.connect("mail-push.example.org", "the-code")
        api.checkInReply = RelayReply.Failed("down")

        now += 20 * 60_000L
        relay.checkIn()
        assertTrue("still trusted after one missed check-in", relay.handlesInstantMail())

        now += 25 * 60_000L
        relay.checkIn()
        assertFalse(relay.handlesInstantMail())
        assertTrue(relay.status.value is RelayStatus.Unreachable)

        // It comes back: trusted again.
        api.checkInReply = RelayReply.Ok("watching")
        relay.checkIn()
        assertTrue(relay.handlesInstantMail())
    }

    @Test
    fun aRelayThatForgotThisDeviceGetsAFreshSignUp() = runBlocking {
        relay.connect("mail-push.example.org", "the-code")
        api.checkInReply = RelayReply.UnknownKey
        api.registerReply = RelayReply.Ok(RelayCredentials("acct", "key-2"))

        relay.checkIn()

        assertEquals("key-2", saved!!.credentials!!.key)
        assertTrue(relay.handlesInstantMail())
    }

    @Test
    fun aRejectedPasswordHandsBackToThePhone() = runBlocking {
        relay.connect("mail-push.example.org", "the-code")
        api.checkInReply = RelayReply.Ok("password_rejected")

        relay.checkIn()

        assertFalse(relay.handlesInstantMail())
        assertTrue(relay.status.value is RelayStatus.PasswordRejected)
    }

    @Test
    fun aPushCountsAsHearingFromTheRelay() = runBlocking {
        relay.connect("mail-push.example.org", "the-code")
        now += 60 * 60_000L
        assertFalse(relay.handlesInstantMail())
        relay.heardFrom()
        assertTrue(relay.handlesInstantMail())
    }

    @Test
    fun disconnectingSignsOffAndForgetsEverything() = runBlocking {
        relay.connect("mail-push.example.org", "the-code")
        relay.disconnect()
        assertEquals("unregister key-1 phone-token", api.calls.last())
        assertNull(saved)
        assertTrue(tokenDeleted)
        assertEquals(RelayStatus.Off, relay.status.value)
        assertFalse(relay.handlesInstantMail())
    }
}
