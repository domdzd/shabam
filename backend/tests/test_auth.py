from __future__ import annotations

from datetime import timedelta

from sqlalchemy import select

from app.models import RefreshToken, User, utcnow
from tests.conftest import auth, register


def test_register_login_me(client):
    tokens = register(client)
    assert tokens["token_type"] == "bearer"
    assert tokens["expires_in"] == 900
    me = client.get("/api/v1/me", headers=auth(tokens))
    assert me.status_code == 200
    assert me.json()["email"] == "user@example.com"

    login = client.post("/api/v1/auth/login", json={"email": " USER@example.com ", "password": "correct horse battery"})
    assert login.status_code == 200
    assert login.json()["user"]["id"] == tokens["user"]["id"]


def test_password_is_hashed_with_argon2id(client):
    register(client)
    db = client.app.state.database.session_factory()
    user = db.scalar(select(User))
    assert user.password_hash.startswith("$argon2id$")
    assert "correct horse battery" not in user.password_hash
    db.close()


def test_generic_login_errors_do_not_reveal_accounts(client):
    register(client)
    wrong_password = client.post("/api/v1/auth/login", json={"email": "user@example.com", "password": "wrong"})
    unknown_user = client.post("/api/v1/auth/login", json={"email": "nobody@example.com", "password": "wrong"})
    assert wrong_password.status_code == unknown_user.status_code == 401
    assert wrong_password.json() == unknown_user.json()


def test_registration_validation(client):
    weak = client.post("/api/v1/auth/register", json={"email": "a@example.com", "password": "short"})
    assert weak.status_code == 422
    assert weak.json()["error"]["code"] == "weak_password"
    bad_email = client.post("/api/v1/auth/register", json={"email": "not-an-email", "password": "long enough password"})
    assert bad_email.status_code == 422
    register(client, "dup@example.com")
    duplicate = client.post("/api/v1/auth/register", json={"email": "dup@example.com", "password": "another long password"})
    assert duplicate.status_code == 409
    # Validation errors never echo submitted values (e.g. passwords).
    response = client.post("/api/v1/auth/register", json={"email": "x@example.com", "password": "p" * 300})
    assert response.status_code == 422
    assert "ppppp" not in response.text


def test_refresh_rotates_and_detects_reuse(client):
    tokens = register(client)
    first_refresh = tokens["refresh_token"]
    rotated = client.post("/api/v1/auth/refresh", json={"refresh_token": first_refresh})
    assert rotated.status_code == 200
    second_refresh = rotated.json()["refresh_token"]
    assert second_refresh != first_refresh

    # Presenting the already-rotated token again revokes the whole family...
    reuse = client.post("/api/v1/auth/refresh", json={"refresh_token": first_refresh})
    assert reuse.status_code == 401
    assert reuse.json()["error"]["code"] == "refresh_token_reused"
    # ...including the newest token.
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": second_refresh}).status_code == 401


def test_refresh_tokens_stored_hashed_and_expire(client):
    tokens = register(client)
    db = client.app.state.database.session_factory()
    row = db.scalar(select(RefreshToken))
    assert row.token_hash != tokens["refresh_token"]
    assert len(row.token_hash) == 64
    row.expires_at = utcnow() - timedelta(seconds=1)
    db.commit()
    db.close()
    expired = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert expired.status_code == 401
    assert expired.json()["error"]["code"] == "refresh_token_expired"


def test_logout_revokes_refresh_token(client):
    tokens = register(client)
    assert client.post("/api/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]}).status_code == 204
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401
    # Unknown tokens also get 204 (no information leak).
    assert client.post("/api/v1/auth/logout", json={"refresh_token": "unknown-token-value"}).status_code == 204


def test_invalid_or_missing_access_token(client):
    assert client.get("/api/v1/me").status_code == 401
    assert client.get("/api/v1/me", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_access_token_from_another_secret_is_rejected(client):
    from app.security import create_access_token

    tokens = register(client)
    forged = create_access_token(tokens["user"]["id"], "y" * 48, 900)
    assert client.get("/api/v1/me", headers={"Authorization": f"Bearer {forged}"}).status_code == 401


def test_login_rate_limited(make_client):
    client = make_client(login_rate_per_ip_per_minute=3)
    for _ in range(3):
        client.post("/api/v1/auth/login", json={"email": "x@example.com", "password": "nope"})
    assert client.post("/api/v1/auth/login", json={"email": "x@example.com", "password": "nope"}).status_code == 429


def test_register_rate_limited(make_client):
    client = make_client(register_rate_per_ip_per_hour=2)
    register(client, "one@example.com")
    register(client, "two@example.com")
    third = client.post("/api/v1/auth/register", json={"email": "three@example.com", "password": "long enough password"})
    assert third.status_code == 429


def test_registration_closed(make_client):
    client = make_client(registration_open=False)
    response = client.post("/api/v1/auth/register", json={"email": "a@example.com", "password": "long enough password"})
    assert response.status_code == 403
    assert client.get("/api/v1/config").json()["accounts"]["registration_open"] is False


def test_accounts_disabled_without_secret(make_client):
    client = make_client(auth_jwt_secret="")
    assert client.get("/api/v1/config").json()["accounts"]["enabled"] is False
    response = client.post("/api/v1/auth/register", json={"email": "a@example.com", "password": "long enough password"})
    assert response.status_code == 503
    # Anonymous recognition tokens keep working without accounts.
    assert client.post("/api/v1/shazamkit/token").status_code == 200


def test_delete_account_requires_password_and_removes_data(client):
    tokens = register(client)
    client.put("/api/v1/sync", headers=auth(tokens), json={"settings": {"data": {"theme": "DARK"}, "updated_at": 1}})
    wrong = client.request("DELETE", "/api/v1/me", headers=auth(tokens), json={"password": "wrong password"})
    assert wrong.status_code == 403
    ok = client.request("DELETE", "/api/v1/me", headers=auth(tokens), json={"password": "correct horse battery"})
    assert ok.status_code == 204
    assert client.get("/api/v1/me", headers=auth(tokens)).status_code == 401
    db = client.app.state.database.session_factory()
    from app.models import SyncSettings

    assert db.scalar(select(User)) is None
    assert db.scalar(select(RefreshToken)) is None
    assert db.scalar(select(SyncSettings)) is None
    db.close()
