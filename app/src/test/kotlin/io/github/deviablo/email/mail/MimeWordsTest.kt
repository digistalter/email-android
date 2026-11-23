// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail

import io.github.deviablo.email.mail.imap.ModifiedUtf7
import org.junit.Assert.assertEquals
import org.junit.Test

class MimeWordsTest {
    @Test fun plainTextStaysUntouched() = assertEquals("Hallo Welt", MimeWords.decode("Hallo Welt"))

    @Test fun base64Word() = assertEquals("Grüße", MimeWords.decode("=?UTF-8?B?R3LDvMOfZQ==?="))

    @Test fun quotedPrintableWithUnderscores() =
        assertEquals("Grüße aus Köln", MimeWords.decode("=?ISO-8859-1?Q?Gr=FC=DFe_aus_K=F6ln?="))

    @Test fun whitespaceBetweenEncodedWordsIsDropped() =
        assertEquals("Wohnungsübergabe", MimeWords.decode("=?UTF-8?Q?Wohnungs?= =?UTF-8?Q?=C3=BCbergabe?="))

    @Test fun mixedPlainAndEncoded() = assertEquals("Re: Grüße", MimeWords.decode("Re: =?UTF-8?Q?Gr=C3=BC=C3=9Fe?="))

    @Test fun unknownCharsetIsKeptAsIs() = assertEquals("=?x-nope?Q?abc?=", MimeWords.decode("=?x-nope?Q?abc?="))

    @Test fun nullBecomesEmpty() = assertEquals("", MimeWords.decode(null))
}

class ModifiedUtf7Test {
    @Test fun umlaut() = assertEquals("Entwürfe", ModifiedUtf7.decode("Entw&APw-rfe"))

    @Test fun ampersand() = assertEquals("A&B", ModifiedUtf7.decode("A&-B"))

    @Test fun ascii() = assertEquals("INBOX/Verein", ModifiedUtf7.decode("INBOX/Verein"))

    @Test fun japanese() = assertEquals("台北", ModifiedUtf7.decode("&U,BTFw-"))
}
