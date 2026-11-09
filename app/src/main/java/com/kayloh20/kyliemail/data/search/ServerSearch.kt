package com.kayloh20.kyliemail.data.search

import javax.mail.Message
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/** Turns search words into an IMAP SEARCH: every word must appear, each in its field or anywhere. */
object ServerSearch {
    fun term(terms: List<SearchQuery.ServerTerm>): SearchTerm? {
        val each = terms.map { (text, field) ->
            when (field) {
                SearchQuery.Field.FROM -> FromStringTerm(text)
                SearchQuery.Field.TO -> OrTerm(RecipientStringTerm(Message.RecipientType.TO, text), RecipientStringTerm(Message.RecipientType.CC, text))
                SearchQuery.Field.SUBJECT -> SubjectTerm(text)
                SearchQuery.Field.BODY -> BodyTerm(text)
                null -> OrTerm(
                    arrayOf(
                        SubjectTerm(text),
                        FromStringTerm(text),
                        RecipientStringTerm(Message.RecipientType.TO, text),
                        BodyTerm(text),
                    ),
                )
            }
        }
        return when (each.size) {
            0 -> null
            1 -> each.single()
            else -> AndTerm(each.toTypedArray())
        }
    }
}
