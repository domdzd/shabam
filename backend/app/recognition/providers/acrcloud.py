"""ACRCloud music recognition adapter (https://www.acrcloud.com).

Protocol verified against ACRCloud's official examples (github.com/acrcloud/webapi_example,
"Identify Protocol V1", and the result parsing in github.com/acrcloud/acrcloud_scan_files_python):
``POST https://<host>/v1/identify`` as multipart/form-data with ``access_key``, ``sample_bytes``,
``timestamp``, ``signature``, ``data_type=audio``, ``signature_version=1`` and the audio as
``sample``. ``signature`` = base64(HMAC-SHA1(access_secret, "POST\\n/v1/identify\\n" + access_key +
"\\n" + data_type + "\\n" + signature_version + "\\n" + timestamp)). Replies carry
``status.code`` (0 = success, 1001 = no result) and matches in ``metadata.music[]``.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import time
from collections.abc import Callable

import httpx

from ..base import ClipInfo, ProviderError, ProviderMatch

HTTP_URI = "/v1/identify"
DATA_TYPE = "audio"
SIGNATURE_VERSION = "1"
NO_RESULT = 1001
_HOST = re.compile(r"^[A-Za-z0-9.-]+$")


def sign(access_key: str, access_secret: str, timestamp: str) -> str:
    string_to_sign = "\n".join(["POST", HTTP_URI, access_key, DATA_TYPE, SIGNATURE_VERSION, timestamp])
    digest = hmac.new(access_secret.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    return base64.b64encode(digest).decode("ascii")


class ACRCloudProvider:
    id = "acrcloud"
    name = "ACRCloud"
    paid = True
    disclosure = (
        "ACRCloud is a paid recognition service billed to this server's owner. Your server sends it the clip."
    )

    def __init__(
        self,
        host: str,
        access_key: str,
        access_secret: str,
        daily_limit: int,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_seconds: float = 10.0,
        clock: Callable[[], float] = time.time,
    ):
        host = host.strip().removeprefix("https://").removeprefix("http://").rstrip("/")
        if not host or not _HOST.match(host) or not access_key or not access_secret:
            raise ValueError("ACRCloud needs a host, access key and access secret")
        self._url = f"https://{host}{HTTP_URI}"
        self._key = access_key
        self._secret = access_secret
        self.daily_limit = daily_limit
        self._transport = transport
        self._timeout = timeout_seconds
        self._clock = clock

    async def identify(self, clip: ClipInfo) -> ProviderMatch | None:
        timestamp = str(int(self._clock()))
        data = {
            "access_key": self._key,
            "sample_bytes": str(len(clip.wav_bytes)),
            "timestamp": timestamp,
            "signature": sign(self._key, self._secret, timestamp),
            "data_type": DATA_TYPE,
            "signature_version": SIGNATURE_VERSION,
        }
        try:
            async with httpx.AsyncClient(transport=self._transport, timeout=self._timeout) as client:
                response = await client.post(self._url, data=data, files={"sample": ("sample.wav", clip.wav_bytes, "audio/wav")})
        except httpx.HTTPError as exc:
            raise ProviderError("ACRCloud could not be reached.", retryable=True) from exc
        try:
            body = response.json()
        except ValueError as exc:
            raise ProviderError(f"ACRCloud returned HTTP {response.status_code}.", retryable=response.status_code >= 500) from exc

        status = body.get("status") if isinstance(body, dict) else None
        code = status.get("code") if isinstance(status, dict) else None
        if code == NO_RESULT:
            return None
        if code != 0:
            # 2xxx: the clip could not be processed (e.g. silence); 3xxx: account/service errors.
            retryable = not (isinstance(code, int) and 2000 <= code < 4000)
            message = status.get("msg") if isinstance(status, dict) else None
            raise ProviderError(f"ACRCloud error {code}: {message or 'unknown'}", retryable=retryable)
        music = (body.get("metadata") or {}).get("music") or []
        return parse_music(music[0]) if music and isinstance(music[0], dict) else None


def parse_music(item: dict) -> ProviderMatch | None:
    title = _text(item.get("title"))
    artists = [a.get("name") for a in item.get("artists") or [] if isinstance(a, dict) and _text(a.get("name"))]
    if not title or not artists:
        return None
    album = item.get("album") if isinstance(item.get("album"), dict) else {}
    external_ids = item.get("external_ids") if isinstance(item.get("external_ids"), dict) else {}
    isrc = external_ids.get("isrc")
    if isinstance(isrc, list):  # the official parser accepts a string or a list
        isrc = isrc[0] if isrc else None
    isrc = _text(isrc)
    external = item.get("external_metadata") if isinstance(item.get("external_metadata"), dict) else {}
    links: dict[str, str] = {}
    spotify_track = ((external.get("spotify") or {}).get("track") or {}) if isinstance(external.get("spotify"), dict) else {}
    if _text(spotify_track.get("id")):
        links["spotify"] = f"https://open.spotify.com/track/{spotify_track['id']}"
    youtube = external.get("youtube") if isinstance(external.get("youtube"), dict) else {}
    if _text(youtube.get("vid")):
        links["video"] = f"https://www.youtube.com/watch?v={youtube['vid']}"
    score = item.get("score")
    acrid = _text(item.get("acrid"))
    return ProviderMatch(
        provider=ACRCloudProvider.id,
        title=title,
        artist=", ".join(artists),
        album=_text(album.get("name")),
        release_date=_text(item.get("release_date")),
        artwork_url=None,  # not part of ACRCloud's standard response
        isrc=isrc,
        provider_track_id=f"isrc:{isrc.upper()}" if isrc else (f"acrcloud:{acrid}" if acrid else None),
        external_urls=links,
        score=max(0.0, min(1.0, score / 100)) if isinstance(score, (int, float)) else None,
    )


def _text(value) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None
