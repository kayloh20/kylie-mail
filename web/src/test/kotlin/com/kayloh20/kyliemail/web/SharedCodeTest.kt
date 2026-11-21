package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.compose.OriginalMessage
import com.kayloh20.kyliemail.data.compose.ReplyBuilder
import com.kayloh20.kyliemail.data.compose.ReplyMode
import com.kayloh20.kyliemail.data.filing.FilingExample
import com.kayloh20.kyliemail.data.filing.FilingFeatures
import com.kayloh20.kyliemail.data.filing.FilingModel
import com.kayloh20.kyliemail.data.mail.ImapConnector
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals

/** The Android app's mail code runs unchanged on the server. Its own tests live with the app. */
class SharedCodeTest {
    @Test
    fun filingSuggestsLikeTheApp() {
        val receipt = FilingFeatures("orders@shop.example", "Shop", emptyList(), "Your order has shipped")
        val model = FilingModel.train(List(5) { FilingExample("Receipts", receipt) })
        assertEquals("Receipts", model.suggest(receipt).first().folder)
    }

    @Test
    fun repliesAreBuiltLikeTheApp() {
        val original = OriginalMessage(
            fromName = "Madison",
            fromAddress = "madison@example.org",
            toList = "me@icloud.com",
            ccList = "",
            subject = "Pep rally",
            sentAt = 0,
            bodyText = "Bring pom-poms",
            messageIdHeader = "<1@example.org>",
        )
        val draft = ReplyBuilder.build(original, ReplyMode.REPLY, setOf("me@icloud.com"), zone = ZoneOffset.UTC, locale = Locale.US)
        assertEquals("madison@example.org", ReplyBuilder.addresses(draft.to).single())
        assertEquals("Re: Pep rally", draft.subject)
    }

    @Test
    fun iCloudUsernamesMatchTheApp() {
        assertEquals(listOf("me@icloud.com", "me"), ImapConnector.usernameCandidates("me@icloud.com"))
    }
}
