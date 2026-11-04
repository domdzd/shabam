"""Small in-memory sliding-window rate limiter.

State lives in the process, so run the API with a single Uvicorn worker (the default in the
provided Docker image). For multiple workers or hosts, replace this with a shared store.
"""

from __future__ import annotations

import math
import threading
import time
from collections import deque
from collections.abc import Callable


class RateLimiter:
    def __init__(self, clock: Callable[[], float] = time.monotonic, max_keys: int = 100_000):
        self._clock = clock
        self._max_keys = max_keys
        self._hits: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def hit(self, key: str, limit: int, window_seconds: float) -> float | None:
        """Records a hit. Returns None if allowed, else the seconds until the next allowed hit."""
        if limit <= 0:
            return None
        now = self._clock()
        with self._lock:
            hits = self._hits.get(key)
            if hits is None:
                if len(self._hits) >= self._max_keys:
                    self._evict(now, window_seconds)
                hits = self._hits.setdefault(key, deque())
            while hits and now - hits[0] >= window_seconds:
                hits.popleft()
            if len(hits) >= limit:
                return max(0.0, window_seconds - (now - hits[0]))
            hits.append(now)
            return None

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()

    def _evict(self, now: float, window_seconds: float) -> None:
        stale = [k for k, v in self._hits.items() if not v or now - v[-1] >= window_seconds]
        for key in stale:
            del self._hits[key]
        if len(self._hits) >= self._max_keys:  # still full: drop the oldest half
            for key in list(self._hits)[: len(self._hits) // 2]:
                del self._hits[key]


def retry_after_header(seconds: float) -> dict[str, str]:
    return {"Retry-After": str(max(1, math.ceil(seconds)))}
