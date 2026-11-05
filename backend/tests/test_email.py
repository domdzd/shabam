"""Password reset and email verification codes, with a fake mailer (no SMTP server is contacted)."""

from __future__ import annotations

import re
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from app.main import create_app
from app.models import EmailCode, utcnow
from tests.conftest import auth, make_settings, register

PASSWORD = "correct horse battery"
NEW_PASSWORD = "a much better passphrase"


class FakeMailer:
    def __init__(self):
        self.sent: list[tuple[str, str, str]] = []

    def send(self, to: str, subject: str, body: str) -> None:
        self.sent.append((to, subject, body))

    def last_code(self) -> str:
        return re.search(r"\b(\d{6})\b", self.sent[-1][2]).group(1)


@pytest.fixture
def mail():
    return FakeMailer()


@pytest.fixture
def email_client(tmp_path, apple_key, mail):
    settings = make_settings(tmp_path, apple_key[0], smtp_host="smtp.example.com", smtp_from="NanoTune <no-reply@example.com>")
    with TestClient(create_app(settings, mailer=mail)) as client:
        yield client


def login(client, password):
    return client.post("/api/v1/auth/login", json={"email": "user@example.com", "password": password})


def test_config_advertises_email_features_only_with_smtp(client, email_client):
    assert client.get("/api/v1/config").json()["accounts"]["password_reset"] is False
    accounts = email_client.get("/api/v1/config").json()["accounts"]
    assert accounts["password_reset"] is True and accounts["email_verification"] is True


def test_endpoints_are_unavailable_without_smtp(client):
    tokens = register(client)
    response = client.post("/api/v1/auth/password-reset/request", json={"email": "user@example.com"})
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "email_unavailable"
    assert client.post("/api/v1/me/email-verification/request", headers=auth(tokens)).status_code == 503


def test_password_reset_flow_signs_out_everywhere(email_client, mail):
    tokens = register(email_client)
    assert email_client.post("/api/v1/auth/password-reset/request", json={"email": "User@Example.com "}).status_code == 202
    to, subject, body = mail.sent[-1]
    assert to == "user@example.com" and "reset" in subject.lower()
    code = mail.last_code()
    assert code in body and "15 minutes" in body

    response = email_client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "user@example.com", "code": code, "new_password": NEW_PASSWORD},
    )
    assert response.status_code == 204
    assert login(email_client, PASSWORD).status_code == 401
    assert login(email_client, NEW_PASSWORD).status_code == 200
    # Old sessions are revoked, and the address now counts as verified.
    assert email_client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401
    fresh = login(email_client, NEW_PASSWORD).json()
    assert email_client.get("/api/v1/me", headers=auth(fresh)).json()["email_verified"] is True
    # A code works only once.
    again = email_client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "user@example.com", "code": code, "new_password": "yet another passphrase"},
    )
    assert again.status_code == 400


def test_reset_request_does_not_reveal_accounts(email_client, mail):
    response = email_client.post("/api/v1/auth/password-reset/request", json={"email": "nobody@example.com"})
    assert response.status_code == 202
    assert mail.sent == []
    unknown = email_client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "nobody@example.com", "code": "123456", "new_password": NEW_PASSWORD},
    )
    assert unknown.status_code == 400 and unknown.json()["error"]["code"] == "invalid_code"


def test_wrong_codes_are_limited(email_client, mail):
    register(email_client)
    email_client.post("/api/v1/auth/password-reset/request", json={"email": "user@example.com"})
    code = mail.last_code()
    wrong = "000000" if code != "000000" else "111111"
    for _ in range(5):
        response = email_client.post(
            "/api/v1/auth/password-reset/confirm",
            json={"email": "user@example.com", "code": wrong, "new_password": NEW_PASSWORD},
        )
        assert response.status_code == 400
    # After five wrong tries even the right code no longer works.
    response = email_client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "user@example.com", "code": code, "new_password": NEW_PASSWORD},
    )
    assert response.status_code == 400
    assert login(email_client, PASSWORD).status_code == 200


def test_expired_code_is_rejected_and_weak_password_keeps_the_code(email_client, mail):
    register(email_client)
    email_client.post("/api/v1/auth/password-reset/request", json={"email": "user@example.com"})
    code = mail.last_code()
    weak = email_client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "user@example.com", "code": code, "new_password": "short"},
    )
    assert weak.status_code == 422
    with email_client.app.state.database.session_factory() as db:
        db.execute(update(EmailCode).values(expires_at=utcnow() - timedelta(seconds=1)))
        db.commit()
    expired = email_client.post(
        "/api/v1/auth/password-reset/confirm",
        json={"email": "user@example.com", "code": code, "new_password": NEW_PASSWORD},
    )
    assert expired.status_code == 400


def test_codes_are_stored_only_as_keyed_hashes(email_client, mail):
    register(email_client)
    email_client.post("/api/v1/auth/password-reset/request", json={"email": "user@example.com"})
    code = mail.last_code()
    with email_client.app.state.database.session_factory() as db:
        rows = list(db.scalars(select(EmailCode)))
    assert len(rows) == 1
    assert code not in rows[0].code_hash and len(rows[0].code_hash) == 64
    # A new request replaces the previous code.
    email_client.post("/api/v1/auth/password-reset/request", json={"email": "user@example.com"})
    with email_client.app.state.database.session_factory() as db:
        assert len(list(db.scalars(select(EmailCode)))) == 1


def test_email_verification_flow(email_client, mail):
    tokens = register(email_client)
    assert email_client.get("/api/v1/me", headers=auth(tokens)).json()["email_verified"] is False
    assert email_client.post("/api/v1/me/email-verification/request", headers=auth(tokens)).status_code == 202
    code = mail.last_code()
    wrong = email_client.post("/api/v1/me/email-verification/confirm", headers=auth(tokens), json={"code": "999999" if code != "999999" else "888888"})
    assert wrong.status_code == 400
    ok = email_client.post("/api/v1/me/email-verification/confirm", headers=auth(tokens), json={"code": code})
    assert ok.status_code == 200 and ok.json()["email_verified"] is True
    # Already verified: nothing more is sent.
    count = len(mail.sent)
    assert email_client.post("/api/v1/me/email-verification/request", headers=auth(tokens)).status_code == 202
    assert len(mail.sent) == count


def test_email_requests_are_rate_limited_per_address(email_client, mail):
    register(email_client)
    statuses = [email_client.post("/api/v1/auth/password-reset/request", json={"email": "user@example.com"}).status_code for _ in range(6)]
    assert statuses[:5] == [202] * 5 and statuses[5] == 429
    assert len(mail.sent) == 5


def test_account_deletion_removes_codes(email_client, mail):
    tokens = register(email_client)
    email_client.post("/api/v1/me/email-verification/request", headers=auth(tokens))
    deleted = email_client.request("DELETE", "/api/v1/me", headers=auth(tokens), json={"password": PASSWORD})
    assert deleted.status_code == 204
    with email_client.app.state.database.session_factory() as db:
        assert list(db.scalars(select(EmailCode))) == []
