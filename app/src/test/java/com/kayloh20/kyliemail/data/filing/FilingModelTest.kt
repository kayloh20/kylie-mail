package com.kayloh20.kyliemail.data.filing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilingModelTest {
    private fun msg(from: String, subject: String, name: String = "", to: List<String> = emptyList()) =
        FilingFeatures(from, name, to, subject)

    private fun filed(folder: String, count: Int, from: String, subject: String, to: List<String> = emptyList()) =
        List(count) { FilingExample(folder, msg(from, subject, to = to)) }

    @Test fun aRegularSenderIsSuggestedConfidentlyWithAReason() {
        val model = FilingModel.train(
            filed("Receipts", 12, "orders@sephora.com", "Your order has shipped") +
                filed("Travel", 5, "hello@jal.com", "Booking confirmed"),
        )
        val top = model.suggest(msg("orders@sephora.com", "Your order is confirmed", name = "Sephora")).first()
        assertEquals("Receipts", top.folder)
        assertTrue("score ${top.score}", top.score >= FilingModel.CONFIDENT)
        assertEquals(Evidence.SENDER, top.evidence)
        assertEquals(12, top.supportingCount)
        assertEquals("Sephora", top.detail)
    }

    @Test fun aSenderSeenOnceIsOnlyAModestSuggestion() {
        val model = FilingModel.train(filed("Receipts", 1, "orders@sephora.com", "Order shipped"))
        val top = model.suggest(msg("orders@sephora.com", "Something else entirely")).first()
        assertTrue("score ${top.score}", top.score < FilingModel.CONFIDENT)
    }

    @Test fun aNewAddressAtAKnownCompanyUsesTheDomain() {
        val model = FilingModel.train(filed("Receipts", 8, "orders@email.sephora.com", "Order shipped"))
        val top = model.suggest(msg("rewards@news.sephora.com", "Order shipped today")).first()
        assertEquals("Receipts", top.folder)
        assertTrue(top.evidence == Evidence.DOMAIN || top.evidence == Evidence.SUBJECT)
    }

    @Test fun personalMailDomainsAreNotTreatedAsCompanies() {
        val model = FilingModel.train(filed("Family", 10, "mom@icloud.com", "Dinner"))
        assertTrue(model.suggest(msg("stranger@icloud.com", "Unrelated topic")).isEmpty())
    }

    @Test fun hideMyEmailAliasesPointToTheirFolder() {
        val alias = "shop_kylie_x7@icloud.com"
        val model = FilingModel.train(
            filed("Shopping", 6, "a@store-one.com", "Sale", to = listOf(alias)) +
                filed("Shopping", 4, "b@store-two.com", "Deals", to = listOf(alias)),
        )
        val top = model.suggest(msg("c@store-three.com", "Hello", to = listOf(alias))).first()
        assertEquals("Shopping", top.folder)
        assertEquals(Evidence.RECIPIENT, top.evidence)
        assertEquals(alias, top.detail)
    }

    @Test fun subjectWordsSeparateFoldersForTheSameSender() {
        val model = FilingModel.train(
            filed("Receipts", 6, "no-reply@apple.com", "Your receipt from Apple") +
                filed("Security", 6, "no-reply@apple.com", "Your Apple Account sign in verification"),
        )
        val receipt = model.suggest(msg("no-reply@apple.com", "Your receipt from Apple"))
        assertEquals("Receipts", receipt.first().folder)
        val security = model.suggest(msg("no-reply@apple.com", "New sign in verification"))
        assertEquals("Security", security.first().folder)
    }

    @Test fun unknownMailGetsNoSuggestion() {
        val model = FilingModel.train(filed("Receipts", 10, "orders@sephora.com", "Order shipped"))
        assertTrue(model.suggest(msg("hi@brand-new.com", "Welcome aboard")).isEmpty())
    }

    @Test fun theCurrentFolderIsExcluded() {
        val model = FilingModel.train(filed("Receipts", 10, "orders@sephora.com", "Order shipped"))
        assertTrue(model.suggest(msg("orders@sephora.com", "Order shipped"), exclude = "Receipts").isEmpty())
    }

    @Test fun aMessageDoesNotCountAsEvidenceForItself() {
        // The only example of this sender is the message itself, so there is nothing to learn from.
        val model = FilingModel.train(filed("Receipts", 1, "orders@sephora.com", "Order shipped"))
        assertTrue(model.suggest(msg("orders@sephora.com", "Order shipped"), self = "Receipts").isEmpty())
    }

    @Test fun suggestionsAreRankedAndLimited() {
        val model = FilingModel.train(
            filed("A", 9, "x@shop.com", "Order") + filed("B", 3, "x@shop.com", "Order") +
                filed("C", 2, "x@shop.com", "Order") + filed("D", 2, "x@shop.com", "Order"),
        )
        val result = model.suggest(msg("x@shop.com", "Order"), limit = 2)
        assertEquals(listOf("A", "B"), result.map { it.folder })
    }

    @Test fun helpers() {
        assertEquals("sephora.com", FilingModel.registrableDomain("a@news.email.sephora.com"))
        assertEquals("bbc.co.uk", FilingModel.registrableDomain("a@news.bbc.co.uk"))
        assertNull(FilingModel.registrableDomain("not-an-address"))
        assertEquals(setOf("order", "shipped"), FilingModel.tokenize("Re: Fwd: Your order #123 has SHIPPED"))
        assertEquals(
            listOf("kylie@icloud.com", "shop_x@icloud.com"),
            FilingModel.parseAddresses("Kylie <Kylie@iCloud.com>, shop_x@icloud.com"),
        )
    }
}
