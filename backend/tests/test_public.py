from __future__ import annotations

import jwt

from app.config import APPLE_MAX_TOKEN_TTL_SECONDS, Settings


def test_health(client):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": "0.3.0"}
    assert response.headers["cache-control"] == "no-store"


def test_config_reports_capabilities(client):
    body = client.get("/api/v1/config").json()
    assert body["shazamkit"] == {"available": True, "token_ttl_seconds": 3600}
    assert body["accounts"]["enabled"] is True
    assert body["accounts"]["password_reset"] is False
    # Default deployment: no server-side engines, so recognition must not be called "enhanced".
    assert body["enhanced"]["available"] is False
    assert body["enhanced"]["providers"] == []
    assert "not configured" in body["enhanced"]["message"]


def test_token_is_valid_es256_jwt_signed_by_server_key(client, apple_key):
    _, private_key = apple_key
    response = client.post("/api/v1/shazamkit/token", headers={"X-NanoTune-Install": "1b4e28ba-2fa1-11d2-883f-0016d3cca427"})
    assert response.status_code == 200
    body = response.json()
    header = jwt.get_unverified_header(body["token"])
    assert header["alg"] == "ES256"
    assert header["kid"] == "KEYID56789"
    claims = jwt.decode(body["token"], private_key.public_key(), algorithms=["ES256"])
    assert claims["iss"] == "TEAMID1234"
    assert claims["exp"] - claims["iat"] == 3600
    assert claims["exp"] - claims["iat"] <= APPLE_MAX_TOKEN_TTL_SECONDS
    assert body["expires_at"] == claims["exp"]
    assert 0 < body["expires_in"] <= 3600
    # The private key is never part of any response.
    assert "PRIVATE KEY" not in response.text


def test_token_is_cached_between_requests(client):
    first = client.post("/api/v1/shazamkit/token").json()["token"]
    second = client.post("/api/v1/shazamkit/token").json()["token"]
    assert first == second


def test_token_unavailable_without_apple_key(make_client):
    client = make_client(with_apple_key=False)
    response = client.post("/api/v1/shazamkit/token")
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "shazamkit_not_configured"
    assert client.get("/api/v1/config").json()["shazamkit"]["available"] is False


def test_invalid_key_file_disables_signing_without_leaking(make_client, tmp_path):
    bad = tmp_path / "bad.p8"
    bad.write_text("-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n")
    client = make_client(apple_private_key_path=str(bad))
    response = client.post("/api/v1/shazamkit/token")
    assert response.status_code == 503
    assert "not a key" not in response.text


def test_anonymous_token_rate_limit_per_install(make_client):
    client = make_client(token_rate_per_install_per_hour=3)
    headers = {"X-NanoTune-Install": "install-aaaa-bbbb"}
    for _ in range(3):
        assert client.post("/api/v1/shazamkit/token", headers=headers).status_code == 200
    limited = client.post("/api/v1/shazamkit/token", headers=headers)
    assert limited.status_code == 429
    assert int(limited.headers["retry-after"]) >= 1
    # Another install behind the same IP is not affected.
    assert client.post("/api/v1/shazamkit/token", headers={"X-NanoTune-Install": "install-cccc-dddd"}).status_code == 200


def test_anonymous_token_rate_limit_per_ip(make_client):
    client = make_client(token_rate_per_ip_per_hour=2)
    assert client.post("/api/v1/shazamkit/token", headers={"X-NanoTune-Install": "aaaaaaaa1"}).status_code == 200
    assert client.post("/api/v1/shazamkit/token", headers={"X-NanoTune-Install": "aaaaaaaa2"}).status_code == 200
    # Rotating install ids does not bypass the per-IP cap.
    assert client.post("/api/v1/shazamkit/token", headers={"X-NanoTune-Install": "aaaaaaaa3"}).status_code == 429


def test_ttl_must_respect_apple_limit(tmp_path):
    import pytest
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        Settings(_env_file=None, shazamkit_token_ttl_seconds=APPLE_MAX_TOKEN_TTL_SECONDS + 1)
    with pytest.raises(ValidationError):
        Settings(_env_file=None, shazamkit_token_ttl_seconds=10)


def test_oversized_request_rejected(client):
    response = client.post("/api/v1/auth/login", content=b"x" * 10, headers={"content-length": str(5_000_000), "content-type": "application/json"})
    assert response.status_code == 413


def test_docs_hidden_in_production(make_client):
    client = make_client(app_env="production")
    assert client.get("/api/docs").status_code == 404
