package com.kayloh20.kyliemail.ui.mail

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val monthDayFormat = DateTimeFormatter.ofPattern("MMM d")
private val shortDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
private val fullFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatListDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMillis <= 0) return ""
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    return when {
        date.toLocalDate() == today -> date.format(timeFormat)
        date.year == today.year -> date.format(monthDayFormat)
        else -> date.format(shortDateFormat)
    }
}

fun formatFullDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (epochMillis <= 0) "" else Instant.ofEpochMilli(epochMillis).atZone(zone).format(fullFormat)
