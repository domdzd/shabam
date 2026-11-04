"""Contract for optional server-side recognition providers ("enhanced recognition").

No third-party provider is enabled by default. To add one (e.g. a paid service you have an
account with), implement :class:`ProviderAdapter`, register it in ``registry.py`` behind its own
``*_ENABLED`` flag, give it a mandatory daily limit, and describe its cost and data handling in
``disclosure`` (shown to users before they opt in). Never call a provider from tests with live keys.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True)
class ClipInfo:
    """A validated, in-memory WAV clip (PCM16 mono). Never written to disk."""

    wav_bytes: bytes
    sample_rate: int
    duration_seconds: float


@dataclass(frozen=True)
class ProviderMatch:
    provider: str
    title: str
    artist: str
    album: str | None = None
    release_date: str | None = None
    artwork_url: str | None = None
    isrc: str | None = None
    provider_track_id: str | None = None
    external_urls: dict[str, str] = field(default_factory=dict)
    #: Normalised 0..1 score, only when the provider reports a real one.
    score: float | None = None


class ProviderError(Exception):
    """A provider failed. ``retryable`` distinguishes outages from permanent misconfiguration."""

    def __init__(self, message: str, retryable: bool = True, rate_limited: bool = False):
        super().__init__(message)
        self.retryable = retryable
        self.rate_limited = rate_limited


class ProviderAdapter(Protocol):
    id: str
    name: str
    paid: bool
    #: Text shown to users before they opt in (cost, what is sent, retention).
    disclosure: str
    #: Mandatory cost control: maximum calls per UTC day across all users.
    daily_limit: int

    async def identify(self, clip: ClipInfo) -> ProviderMatch | None:
        """Returns a match, None for "no match", or raises :class:`ProviderError`."""
        ...
