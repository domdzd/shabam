from __future__ import annotations

from tests.conftest import auth, register


def recognition(record_id: str, updated_at: int, **extra) -> dict:
    body = {
        "id": record_id,
        "track_key": f"id:shazam:{record_id}",
        "title": f"Title {record_id}",
        "artist": "Artist",
        "provider_id": "shazamkit",
        "recognized_at": 1_700_000_000_000,
        "mode": "manual",
        "updated_at": updated_at,
    }
    body.update(extra)
    return body


def test_push_then_pull_round_trip(client):
    tokens = register(client)
    push = client.put(
        "/api/v1/sync",
        headers=auth(tokens),
        json={
            "recognitions": [recognition("r1", 10, genres=["Pop"], external_urls={"apple_music": "https://music.apple.com/x"})],
            "favourites": [{"track_key": "id:shazam:r1", "title": "Title r1", "artist": "Artist", "created_at": 5, "updated_at": 10}],
            "settings": {"data": {"theme": "LIGHT"}, "updated_at": 7},
        },
    )
    assert push.status_code == 200, push.text
    assert push.json() == {"cursor": 3, "accepted": 3, "ignored": 0}

    pulled = client.get("/api/v1/sync", params={"since": 0}, headers=auth(tokens)).json()
    assert pulled["cursor"] == 3
    assert pulled["has_more"] is False
    assert [r["id"] for r in pulled["recognitions"]] == ["r1"]
    assert pulled["recognitions"][0]["genres"] == ["Pop"]
    assert pulled["favourites"][0]["track_key"] == "id:shazam:r1"
    assert pulled["settings"] == {"data": {"theme": "LIGHT"}, "updated_at": 7}

    # Nothing new since the cursor.
    empty = client.get("/api/v1/sync", params={"since": 3}, headers=auth(tokens)).json()
    assert empty["recognitions"] == [] and empty["favourites"] == [] and empty["settings"] is None


def test_last_writer_wins_and_tombstones(client):
    tokens = register(client)
    client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("r1", 100)]})
    older = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("r1", 50, title="Stale")]}).json()
    assert older["ignored"] == 1
    tie = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("r1", 100, title="Tie")]}).json()
    assert tie["ignored"] == 1
    newer = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("r1", 200, deleted=True)]}).json()
    assert newer["accepted"] == 1

    pulled = client.get("/api/v1/sync", params={"since": 0}, headers=auth(tokens)).json()
    assert len(pulled["recognitions"]) == 1
    assert pulled["recognitions"][0]["deleted"] is True
    assert pulled["recognitions"][0]["title"] == "Title r1"


def test_sync_is_isolated_between_accounts(client):
    alice = register(client, "alice@example.com")
    bob = register(client, "bob@example.com")
    client.put("/api/v1/sync", headers=auth(alice), json={"recognitions": [recognition("shared-id", 1)]})
    bob_view = client.get("/api/v1/sync", params={"since": 0}, headers=auth(bob)).json()
    assert bob_view["recognitions"] == []
    # Bob using the same record id creates his own copy and does not touch Alice's.
    client.put("/api/v1/sync", headers=auth(bob), json={"recognitions": [recognition("shared-id", 99, title="Bob's")]})
    alice_view = client.get("/api/v1/sync", params={"since": 0}, headers=auth(alice)).json()
    assert alice_view["recognitions"][0]["title"] == "Title shared-id"


def test_pagination_never_skips_records(make_client):
    client = make_client(sync_rate_per_user_per_minute=1000)
    tokens = register(client)
    import app.routers.sync as sync_module

    original = sync_module.PAGE_SIZE
    sync_module.PAGE_SIZE = 3
    try:
        client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition(f"r{i}", i + 1) for i in range(7)]})
        seen: list[str] = []
        cursor = 0
        for _ in range(10):
            page = client.get("/api/v1/sync", params={"since": cursor}, headers=auth(tokens)).json()
            seen += [r["id"] for r in page["recognitions"]]
            cursor = page["cursor"]
            if not page["has_more"]:
                break
        assert sorted(seen) == sorted(f"r{i}" for i in range(7))
        assert len(seen) == 7
    finally:
        sync_module.PAGE_SIZE = original


def test_batch_and_quota_limits(make_client):
    client = make_client(sync_max_batch=2, sync_max_records_per_user=3)
    tokens = register(client)
    too_big = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition(f"r{i}", 1) for i in range(3)]})
    assert too_big.status_code == 413
    assert client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("a", 1), recognition("b", 1)]}).status_code == 200
    assert client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("c", 1)]}).status_code == 200
    full = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("d", 1)]})
    assert full.status_code == 413
    assert full.json()["error"]["code"] == "quota_exceeded"


def test_sync_requires_auth(client):
    assert client.get("/api/v1/sync").status_code == 401
    assert client.put("/api/v1/sync", json={}).status_code == 401


def test_field_length_validation(client):
    tokens = register(client)
    response = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition("r1", 1, title="x" * 600)]})
    assert response.status_code == 422


def test_concurrent_pushes_get_unique_increasing_sequence_numbers(make_client):
    import threading

    client = make_client(sync_rate_per_user_per_minute=10_000)
    tokens = register(client)
    errors: list[str] = []

    def push(start: int) -> None:
        for i in range(start, start + 10):
            response = client.put("/api/v1/sync", headers=auth(tokens), json={"recognitions": [recognition(f"t{i}", 1)]})
            if response.status_code != 200:
                errors.append(response.text)

    threads = [threading.Thread(target=push, args=(n * 100,)) for n in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert errors == []

    db = client.app.state.database.session_factory()
    from sqlalchemy import select

    from app.models import SyncRecognition, User

    seqs = [row.seq for row in db.scalars(select(SyncRecognition))]
    user = db.scalar(select(User))
    db.close()
    assert len(seqs) == 40
    assert len(set(seqs)) == 40
    assert max(seqs) == user.sync_seq == 40
