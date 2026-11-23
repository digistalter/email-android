// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** List dates as in the Linux app: today the time, "yesterday", the weekday within a week, else a short date. */
object Dates {
    fun short(millis: Long, yesterday: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(), today: LocalDate = LocalDate.now(zone)): String {
        if (millis <= 0) return ""
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        val date = time.toLocalDate()
        return when {
            date == today -> time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            date == today.minusDays(1) -> yesterday
            date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        }
    }
}
