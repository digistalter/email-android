// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

/** Placeholder content until the IMAP layer exists. Mirrors the wireframe. */

data class Account(val name: String, val address: String, val unread: Int)

data class Message(val from: String, val time: String, val preview: String)

data class MailThread(
    val id: Int,
    val senders: String,
    val subject: String,
    val time: String,
    val unread: Boolean = false,
    val starred: Boolean = false,
    val hasAttachment: Boolean = false,
    val messages: List<Message>,
)

object SampleData {
    val accounts = listOf(
        Account("Privat", "max.berger@example.org", 0),
        Account("Arbeit", "m.berger@nordlicht.example", 4),
    )

    val threads = listOf(
        MailThread(
            1, "Lena, Ich", "Wohnungsübergabe am Samstag", "17:44",
            messages = listOf(
                Message("Lena Koch", "Fr.", "Hallo Max, passt dir Samstag?"),
                Message("Ich", "Gestern", "Samstag passt. Um wie viel Uhr?"),
                Message("Lena Koch", "Heute, 17:44", "Perfekt, danke!"),
            ),
        ),
        MailThread(2, "Sophie Wagner", "Geburtstagsessen – wer bringt den Kuchen?", "15:24", unread = true,
            messages = listOf(Message("Sophie Wagner", "Heute, 15:24", "Wer bringt den Kuchen mit?"))),
        MailThread(3, "Jonas Weber", "Fotos vom Wochenende", "13:24", starred = true, hasAttachment = true,
            messages = listOf(Message("Jonas Weber", "Heute, 13:24", "Hier sind die Fotos."))),
        MailThread(4, "Stadtbücherei", "Deine Ausleihe endet in 3 Tagen", "Gestern", unread = true,
            messages = listOf(Message("Stadtbücherei", "Gestern", "Bitte denke an die Rückgabe."))),
        MailThread(5, "Reisebüro", "Deine Buchung: Berlin → Hamburg", "Gestern", hasAttachment = true,
            messages = listOf(Message("Reisebüro", "Gestern", "Deine Tickets findest du im Anhang."))),
        MailThread(6, "Ich, Paul", "Fahrrad noch zu haben?", "Do.",
            messages = listOf(
                Message("Paul Lange", "Mi.", "Ist das Fahrrad noch zu haben?"),
                Message("Ich", "Do.", "Ja, du kannst es am Wochenende ansehen."),
            )),
        MailThread(7, "Gemeinschaftsgarten", "Frühjahrspflanzung am Samstag", "Mi.",
            messages = listOf(Message("Gemeinschaftsgarten", "Mi.", "Wir pflanzen am Samstag."))),
    )
}
