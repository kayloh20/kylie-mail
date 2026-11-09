package com.kayloh20.kyliemail.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ImapConnectorTest {
    @Test fun appleDomainsAlsoTryTheBareName() {
        assertEquals(listOf("kylie@icloud.com", "kylie"), ImapConnector.usernameCandidates("kylie@icloud.com"))
        assertEquals(listOf("kylie@me.com", "kylie"), ImapConnector.usernameCandidates(" kylie@me.com "))
        assertEquals(listOf("kylie@Mac.com", "kylie"), ImapConnector.usernameCandidates("kylie@Mac.com"))
    }

    @Test fun otherDomainsUseTheFullAddressOnly() {
        assertEquals(listOf("kylie@example.com"), ImapConnector.usernameCandidates("kylie@example.com"))
    }
}
