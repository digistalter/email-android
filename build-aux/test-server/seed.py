# SPDX-License-Identifier: GPL-3.0-or-later
"""Waits for the local GreenMail server and delivers a handful of sample mails to max.berger."""
import smtplib
import socket
import time
from email.message import EmailMessage
from email.utils import formatdate

TO = "max.berger@example.org"
MAILS = [
    ("Lena Koch <lena.koch@example.org>", "Wohnungsübergabe am Samstag", "Hallo Max, passt dir Samstag?", None),
    ("Sophie Wagner <sophie@example.net>", "Geburtstagsessen – wer bringt den Kuchen?", "Wer bringt den Kuchen mit?", None),
    ("Jonas Weber <jonas@example.net>", "Fotos vom Wochenende", "Hier sind die Fotos.", ("foto.jpg", b"\xff\xd8\xff\xe0 test")),
    ("Stadtbücherei <info@buecherei.example>", "Deine Ausleihe endet in 3 Tagen", "Bitte denke an die Rückgabe.", None),
    ("Reisebüro <service@reise.example>", "Deine Buchung: Berlin → Hamburg", "Deine Tickets findest du im Anhang.", ("tickets.pdf", b"%PDF-1.4 test")),
]


def wait_for(port: int) -> None:
    for _ in range(60):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return
        except OSError:
            time.sleep(1)
    raise SystemExit(f"GreenMail did not open port {port}")


wait_for(3025)
wait_for(3993)
with smtplib.SMTP("127.0.0.1", 3025) as smtp:
    for sender, subject, text, attachment in MAILS:
        msg = EmailMessage()
        msg["From"], msg["To"], msg["Subject"], msg["Date"] = sender, TO, subject, formatdate(localtime=True)
        msg.set_content(text)
        if attachment:
            name, data = attachment
            msg.add_attachment(data, maintype="application", subtype="octet-stream", filename=name)
        smtp.send_message(msg)
print(f"{len(MAILS)} Beispiel-Mails zugestellt.")
