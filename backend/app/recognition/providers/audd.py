"""AudD music recognition adapter (https://audd.io).

Wire format verified against AudD's official Go SDK (github.com/AudDMusic/audd-go, 2026-11):
``POST https://api.audd.io/`` as multipart/form-data with ``api_token``, the audio as ``file`` and
optional ``return`` (comma-separated metadata blocks). The JSON reply has ``status`` ("success" /
"error"), ``result`` (``null`` = no match) and, on errors, ``error.error_code`` /
``error.error_message``. Only the first 12 seconds of a clip are analysed.
"""

from __future__ import annotations

from urllib.parse import urlparse

import httpx

from ..base import ClipInfo, ProviderError, ProviderMatch

API_URL = "https://api.audd.io/"

# Error classes from the SDK's sentinelForCode().
_AUTH = {900, 901, 903}
_QUOTA = {902}
_SUBSCRIPTION = {904, 905}
_INVALID_REQUEST = {50, 51, 600, 601, 602, 700, 701, 702, 906}
_INVALID_AUDIO = {300, 400, 500}
_RATE_LIMIT = {611}
_BLOCKED = {19, 31337}
_NEEDS_UPDATE = {20}
_DEPRECATED_PARAMS = 51


class AudDProvider:
    id = "audd"
    name = "AudD"
    paid = True
    disclosure = (
        "AudD (audd.io) is a paid recognition service billed to this server's owner. Your server sends "
        "it the clip; AudD analyses the first 12 seconds."
    )

    def __init__(
        self,
        api_token: str,
        daily_limit: int,
        return_metadata: str = "apple_music,spotify",
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_seconds: float = 10.0,
    ):
        if not api_token:
            raise ValueError("AudD needs an api_token")
        self._token = api_token
        self.daily_limit = daily_limit
        self._return = return_metadata
        self._transport = transport
        self._timeout = timeout_seconds

    async def identify(self, clip: ClipInfo) -> ProviderMatch | None:
        data = {"api_token": self._token}
        if self._return:
            data["return"] = self._return
        try:
            async with httpx.AsyncClient(transport=self._transport, timeout=self._timeout) as client:
                response = await client.post(API_URL, data=data, files={"file": ("clip.wav", clip.wav_bytes, "audio/wav")})
        except httpx.HTTPError as exc:
            raise ProviderError("AudD could not be reached.", retryable=True) from exc
        try:
            body = response.json()
        except ValueError as exc:
            raise ProviderError(f"AudD returned HTTP {response.status_code}.", retryable=response.status_code >= 500) from exc
        if not isinstance(body, dict):
            raise ProviderError("AudD returned an unexpected response.", retryable=True)

        status = body.get("status")
        result = body.get("result")
        if status == "error":
            error = body.get("error") if isinstance(body.get("error"), dict) else {}
            code = _int(error.get("error_code"))
            # Code 51 = deprecated parameters but a usable result is included (SDK passes it through).
            if not (code == _DEPRECATED_PARAMS and result):
                raise _error_for(code)
        elif status != "success":
            raise ProviderError("AudD returned an unexpected status.", retryable=True)

        if not isinstance(result, dict):
            return None
        return parse_result(result)


def parse_result(result: dict) -> ProviderMatch | None:
    title = _text(result.get("title"))
    artist = _text(result.get("artist"))
    if not title or not artist:
        return None
    apple = result.get("apple_music") if isinstance(result.get("apple_music"), dict) else {}
    spotify = result.get("spotify") if isinstance(result.get("spotify"), dict) else {}
    isrc = _text(result.get("isrc")) or _text(apple.get("isrc"))
    links: dict[str, str] = {}
    if _text(apple.get("url")):
        links["apple_music"] = apple["url"]
    spotify_id = _text(spotify.get("id"))
    if spotify_id:
        links["spotify"] = f"https://open.spotify.com/track/{spotify_id}"
    song_link = _text(result.get("song_link"))
    if song_link:
        links["song_link"] = song_link
    return ProviderMatch(
        provider=AudDProvider.id,
        title=title,
        artist=artist,
        album=_text(result.get("album")) or _text(apple.get("albumName")),
        release_date=_text(result.get("release_date")) or _text(apple.get("releaseDate")),
        artwork_url=_artwork(apple) or _lis_tn_thumbnail(song_link),
        isrc=isrc,
        provider_track_id=f"isrc:{isrc.upper()}" if isrc else None,
        external_urls=links,
        score=None,  # AudD does not report a confidence score.
    )


def _artwork(apple: dict) -> str | None:
    # Apple Music artwork objects carry a URL template with {w}/{h} placeholders.
    artwork = apple.get("artwork") if isinstance(apple.get("artwork"), dict) else {}
    template = _text(artwork.get("url"))
    if not template:
        return None
    return template.replace("{w}", "600").replace("{h}", "600")


def _lis_tn_thumbnail(song_link: str | None) -> str | None:
    # Same rule as the SDK's ThumbnailURL(): only for lis.tn song links.
    if not song_link:
        return None
    try:
        parsed = urlparse(song_link)
    except ValueError:
        return None
    if parsed.hostname != "lis.tn":
        return None
    return song_link + ("&" if parsed.query else "?") + "thumb"


def _error_for(code: int) -> ProviderError:
    if code in _AUTH:
        return ProviderError("AudD rejected the API token.", retryable=False)
    if code in _QUOTA:
        return ProviderError("AudD quota exceeded.", retryable=False, rate_limited=True)
    if code in _SUBSCRIPTION:
        return ProviderError("AudD subscription does not cover this request.", retryable=False)
    if code in _RATE_LIMIT:
        return ProviderError("AudD rate limit reached.", retryable=True, rate_limited=True)
    if code in _INVALID_AUDIO:
        return ProviderError("AudD could not process the audio.", retryable=False)
    if code in _INVALID_REQUEST | _BLOCKED | _NEEDS_UPDATE:
        return ProviderError(f"AudD refused the request (code {code}).", retryable=False)
    return ProviderError(f"AudD server error (code {code}).", retryable=True)


def _int(value) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _text(value) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None
