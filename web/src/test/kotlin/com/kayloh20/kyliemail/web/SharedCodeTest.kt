package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.compose.OriginalMessage
import com.kayloh20.kyliemail.data.compose.ReplyBuilder
import com.kayloh20.kyliemail.data.compose.ReplyMode
import com.kayloh20.kyliemail.data.filing.FilingExample
import com.kayloh20.kyliemail.data.filing.FilingFeatures
import com.kayloh20.kyliemail.data.filing.FilingModel
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.search.SearchQuery
import com.kayloh20.kyliemail.data.search.ServerSearch
import javax.mail.search.AndTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm
import kotlin.test.assertTrue
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

    @Test
    fun searchUnderstandsFiltersLikeTheApp() {
        val query = SearchQuery.parse("from:shop subject:\"order has\" pom")
        assertEquals(
            listOf(
                SearchQuery.ServerTerm("shop", SearchQuery.Field.FROM),
                SearchQuery.ServerTerm("order has", SearchQuery.Field.SUBJECT),
                SearchQuery.ServerTerm("pom", null),
            ),
            query.serverTerms,
        )
        val term = ServerSearch.term(query.serverTerms) as AndTerm
        assertTrue(term.terms[0] is FromStringTerm)
        assertTrue(term.terms[1] is SubjectTerm)
        assertTrue(term.terms[2] is OrTerm, "a plain word matches anywhere")
    }
}
