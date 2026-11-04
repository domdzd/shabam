"""Builds the list of server-side providers from configuration.

The default deployment ships **no** enabled provider: AcoustID cannot match microphone snippets
(it fingerprints whole files and filters by total duration), and paid services (AudD, ACRCloud,
...) must not be enabled without an explicit flag, a documented cost/limit and user disclosure.
See docs/RECOGNITION_PROVIDERS.md for how to add an adapter.
"""

from __future__ import annotations

from ..config import Settings
from .base import ProviderAdapter


def build_providers(settings: Settings) -> list[ProviderAdapter]:
    providers: list[ProviderAdapter] = []
    # Register adapters here, each behind its own *_ENABLED flag and a mandatory daily limit, e.g.:
    #
    #   if settings.example_enabled and settings.example_api_key and settings.example_daily_limit > 0:
    #       providers.append(ExampleProvider(settings.example_api_key, settings.example_daily_limit))
    #
    return providers
