"""Reconciles matches from several providers without pretending they independently agree."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from .base import ProviderMatch

_FEAT = re.compile(r"\s*[\(\[]?\b(feat\.?|ft\.?|featuring)\b.*$", re.IGNORECASE)
_BRACKETS = re.compile(r"\s*[\(\[][^\)\]]*(remaster|version|edit|mix|live|mono|stereo)[^\)\]]*[\)\]]", re.IGNORECASE)
_NON_ALNUM = re.compile(r"[^0-9a-z]+")


def normalize(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c)).lower()
    stripped = _BRACKETS.sub("", stripped)
    stripped = _FEAT.sub("", stripped)
    return _NON_ALNUM.sub(" ", stripped).strip()


def song_key(match: ProviderMatch) -> str:
    if match.isrc:
        return "isrc:" + match.isrc.strip().upper()
    return "meta:" + normalize(match.artist) + "|" + normalize(match.title)


@dataclass
class Reconciled:
    best: ProviderMatch
    providers: list[str]
    agreement: str  # "single" | "agree" | "conflict"
    score: float | None


def _same_song(a: ProviderMatch, b: ProviderMatch) -> bool:
    if a.isrc and b.isrc:
        return a.isrc.strip().upper() == b.isrc.strip().upper()
    return normalize(a.artist) == normalize(b.artist) and normalize(a.title) == normalize(b.title)


def reconcile(matches: list[ProviderMatch], priority: list[str]) -> Reconciled | None:
    """Groups matches that describe the same song and picks the best-supported group.

    * One match → ``single``.
    * Several providers naming the same song → ``agree``, metadata merged in priority order.
    * Providers naming different songs → ``conflict``; the group with the most providers wins,
      ties broken by provider priority. The UI shows that engines disagreed.
    """
    if not matches:
        return None
    rank = {provider: index for index, provider in enumerate(priority)}
    groups: list[list[ProviderMatch]] = []
    for match in sorted(matches, key=lambda m: rank.get(m.provider, len(rank))):
        for group in groups:
            if _same_song(group[0], match):
                group.append(match)
                break
        else:
            groups.append([match])
    groups.sort(key=lambda g: (-len(g), rank.get(g[0].provider, len(rank))))
    winner = groups[0]
    primary = winner[0]

    def first(attr: str):
        for m in winner:
            value = getattr(m, attr)
            if value:
                return value
        return None

    links: dict[str, str] = {}
    for m in reversed(winner):
        links.update(m.external_urls)
    best = ProviderMatch(
        provider=primary.provider,
        title=primary.title,
        artist=primary.artist,
        album=first("album"),
        release_date=first("release_date"),
        artwork_url=first("artwork_url"),
        isrc=first("isrc"),
        provider_track_id=primary.provider_track_id,
        external_urls=links,
        score=primary.score,
    )
    scores = [m.score for m in winner if m.score is not None]
    if len(groups) > 1:
        agreement = "conflict"
    elif len(winner) > 1:
        agreement = "agree"
    else:
        agreement = "single"
    return Reconciled(
        best=best,
        providers=[m.provider for m in winner],
        agreement=agreement,
        score=max(scores) if scores else None,
    )
