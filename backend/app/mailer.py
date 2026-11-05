"""Sends one-time codes by email over SMTP (password reset, email verification).

Only the standard library is used. Messages are plain text and contain nothing but the code and
what it is for. Delivery runs after the HTTP response (FastAPI background task), so response times
do not reveal whether an address has an account.
"""

from __future__ import annotations

import logging
import smtplib
import ssl
from email.message import EmailMessage
from typing import Protocol

from .config import Settings

log = logging.getLogger("nanotune.mail")


class Mailer(Protocol):
    def send(self, to: str, subject: str, body: str) -> None: ...


class SmtpMailer:
    def __init__(self, settings: Settings):
        self.settings = settings

    def send(self, to: str, subject: str, body: str) -> None:
        settings = self.settings
        message = EmailMessage()
        message["From"] = settings.smtp_from
        message["To"] = to
        message["Subject"] = subject
        message.set_content(body)
        context = ssl.create_default_context()
        try:
            if settings.smtp_security == "ssl":
                client: smtplib.SMTP = smtplib.SMTP_SSL(settings.smtp_host, settings.smtp_port, timeout=settings.smtp_timeout_seconds, context=context)
            else:
                client = smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=settings.smtp_timeout_seconds)
            with client:
                if settings.smtp_security == "starttls":
                    client.starttls(context=context)
                if settings.smtp_username:
                    client.login(settings.smtp_username, settings.smtp_password)
                client.send_message(message)
        except (OSError, smtplib.SMTPException) as exc:
            # Never log the recipient's address or the code.
            log.error("Sending email failed: %s", type(exc).__name__)


def deliver(mailer: Mailer, to: str, subject: str, body: str) -> None:
    """Background-task entry point: a failing mailer must never break the request."""
    try:
        mailer.send(to, subject, body)
    except Exception:  # noqa: BLE001
        log.exception("Mailer crashed")
