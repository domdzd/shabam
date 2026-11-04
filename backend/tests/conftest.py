from __future__ import annotations

import os
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


@pytest.fixture(scope="session")
def apple_key(tmp_path_factory) -> tuple[Path, ec.EllipticCurvePrivateKey]:
    """A throwaway P-256 key standing in for an Apple Media Services .p8 file."""
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    path = tmp_path_factory.mktemp("secrets") / "apple_media_services.p8"
    path.write_bytes(pem)
    os.chmod(path, 0o600)
    return path, key


def make_settings(tmp_path: Path, apple_key_path: Path | None, **overrides) -> Settings:
    values = dict(
        app_env="test",
        database_url=f"sqlite:///{tmp_path / 'test.db'}",
        apple_team_id="TEAMID1234" if apple_key_path else "",
        apple_key_id="KEYID56789" if apple_key_path else "",
        apple_private_key_path=str(apple_key_path) if apple_key_path else "/nonexistent.p8",
        auth_jwt_secret="x" * 48,
        run_migrations_on_start=True,
    )
    values.update(overrides)
    return Settings(_env_file=None, **values)


@pytest.fixture
def settings(tmp_path, apple_key) -> Settings:
    return make_settings(tmp_path, apple_key[0])


@pytest.fixture
def client(settings) -> TestClient:
    with TestClient(create_app(settings)) as test_client:
        yield test_client


@pytest.fixture
def make_client(tmp_path, apple_key):
    """Factory for clients with custom settings / providers."""
    clients: list[TestClient] = []

    def factory(with_apple_key: bool = True, providers=None, **overrides) -> TestClient:
        settings = make_settings(tmp_path, apple_key[0] if with_apple_key else None, **overrides)
        test_client = TestClient(create_app(settings, providers=providers))
        test_client.__enter__()
        clients.append(test_client)
        return test_client

    yield factory
    for test_client in clients:
        test_client.__exit__(None, None, None)


def register(client: TestClient, email: str = "user@example.com", password: str = "correct horse battery") -> dict:
    response = client.post("/api/v1/auth/register", json={"email": email, "password": password, "device_name": "Pixel"})
    assert response.status_code == 201, response.text
    return response.json()


def auth(tokens: dict) -> dict:
    return {"Authorization": f"Bearer {tokens['access_token']}"}
