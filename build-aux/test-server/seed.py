# SPDX-License-Identifier: GPL-3.0-or-later
"""Waits for the local GreenMail server and fills max.berger's mailbox with realistic sample mail:
plain and HTML mails, a remote (tracking) image, an inline logo, attachments and a conversation
with an own reply in "Sent"."""
import email.utils
import imaplib
import os
import socket
import ssl
import time
from email.message import EmailMessage
from email.utils import formatdate, make_msgid

HERE = os.path.dirname(os.path.abspath(__file__))
SMTP_PORT = int(os.environ.get("SEED_SMTP_PORT", "3025"))
IMAP_PORT = int(os.environ.get("SEED_IMAP_PORT", "3993"))
TO = "max.berger@example.org"
LOGO = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000006000000020080200000062a2ea030000007a4944415478daedd9b11140"
    "30148061713a0a9b68ecc202a6b2806114b189c200064095438aef2f1529be7bef923ba11e62a1e74a0480000102946f"
    "d5f5d33ef52927b6f36a82ac98000102f4d72df646c7d2e56fd18c9b09b262800001020408903e7e28debec14c901503"
    "244080d20a7e3d9b20408000e5db09c8bf09de507873aa0000000049454e44ae426082")


def wait_for(port: int) -> None:
    for _ in range(60):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return
        except OSError:
            time.sleep(1)
    raise SystemExit(f"GreenMail did not open port {port}")


def mail(sender, subject, text, html=None, attachment=None, reply_to=None, msgid=None, minutes_ago=0):
    m = EmailMessage()
    m["From"], m["To"], m["Subject"] = sender, TO, subject
    m["Date"] = formatdate(time.time() - minutes_ago * 60, localtime=True)
    m["Message-ID"] = msgid or make_msgid(domain="example.org")
    if reply_to:
        m["In-Reply-To"] = reply_to
        m["References"] = reply_to
    m.set_content(text)
    if html:
        m.add_alternative(html, subtype="html")
        if "cid:logo" in html:
            m.get_payload()[1].add_related(LOGO, "image", "png", cid="<logo@example.org>")
    if attachment:
        name, data = attachment
        m.add_attachment(data, maintype="application", subtype="octet-stream", filename=name)
    return m


# Only SMTP is probed: a bare connect to the TLS port makes GreenMail log a scary stack trace.
wait_for(SMTP_PORT)
first = "<wohnung-1@example.org>"
mine = "<wohnung-2@example.org>"
inbox = [
    mail("Lena Koch <lena.koch@example.org>", "Wohnungsübergabe am Samstag", "Hallo Max, passt dir Samstag?", msgid=first, minutes_ago=600),
    mail("Lena Koch <lena.koch@example.org>", "Re: Wohnungsübergabe am Samstag", "Perfekt, danke! Bis Samstag um 10.", reply_to=mine, minutes_ago=5),
    mail("Sophie Wagner <sophie@example.net>", "Geburtstagsessen – wer bringt den Kuchen?", "Wer bringt den Kuchen mit?", minutes_ago=60),
    mail("Jonas Weber <jonas@example.net>", "Fotos vom Wochenende", "Hier sind die Fotos.", attachment=("foto.jpg", b"\xff\xd8\xff\xe0 test"), minutes_ago=120),
    mail("Stadtbücherei <info@buecherei.example>", "Deine Ausleihe endet in 3 Tagen", "Bitte denke an die Rückgabe.",
         html="<h2 style='color:#0b57d0'>Stadtbücherei</h2><p>Deine Ausleihe <b>„Der Schwarm“</b> endet in 3 Tagen.</p>"
              "<p><img src='https://tracker.example/pixel.gif' width='1' height='1'><img src='cid:logo@example.org' alt='Logo'></p>"
              "<p><a href='https://buecherei.example/konto'>Zum Konto</a> · <a href='https://buecherei.example@evil.example/'>Verlängern</a></p>",
         minutes_ago=1500),
    mail("Reisebüro <service@reise.example>", "Deine Buchung: Berlin → Hamburg", "Deine Tickets findest du im Anhang.",
         attachment=("tickets.pdf", b"%PDF-1.4 test"), minutes_ago=1600),
]
# Own reply in "Sent", so the conversation shows "Lena, Ich · 3".
reply = EmailMessage()
reply["From"], reply["To"], reply["Subject"] = "Max Berger <max.berger@example.org>", "lena.koch@example.org", "Re: Wohnungsübergabe am Samstag"
reply["Date"] = formatdate(time.time() - 300 * 60, localtime=True)
reply["Message-ID"], reply["In-Reply-To"], reply["References"] = mine, first, first
reply.set_content("Samstag passt. Um wie viel Uhr?")
ctx = ssl.create_default_context(cafile=os.path.join(HERE, "..", "..", "app", "src", "test", "resources", "test_ca.pem"))
# The keytool-made test CA has no key-usage extension, which Python 3.13+ insists on in strict mode.
ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT
with imaplib.IMAP4_SSL("localhost", IMAP_PORT, ssl_context=ctx) as imap:
    imap.login(TO, "secret")
    # APPEND instead of SMTP, so the server's arrival time matches the Date header.
    for m in inbox:
        when = email.utils.parsedate_to_datetime(m["Date"])
        imap.append("INBOX", None, imaplib.Time2Internaldate(when), m.as_bytes())
    imap.create("Sent")
    imap.append("Sent", "(\\Seen)", imaplib.Time2Internaldate(email.utils.parsedate_to_datetime(reply["Date"])), reply.as_bytes())
    imap.logout()
print(f"{len(inbox)} Beispiel-Mails zugestellt, 1 Antwort in „Gesendet“.")
