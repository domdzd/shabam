"""Strict validation of uploaded clips. Only small PCM16 mono WAV files are accepted."""

from __future__ import annotations

import struct

from .base import ClipInfo


class InvalidClip(ValueError):
    pass


def parse_wav(data: bytes, max_seconds: float) -> ClipInfo:
    if len(data) < 44 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise InvalidClip("Upload must be a RIFF/WAVE file.")
    offset = 12
    fmt: tuple[int, int, int, int] | None = None
    pcm_length: int | None = None
    while offset + 8 <= len(data):
        chunk_id = data[offset : offset + 4]
        (size,) = struct.unpack("<I", data[offset + 4 : offset + 8])
        body = offset + 8
        if chunk_id == b"fmt ":
            if size < 16 or body + 16 > len(data):
                raise InvalidClip("Malformed fmt chunk.")
            audio_format, channels, sample_rate, _byte_rate, _block_align, bits = struct.unpack("<HHIIHH", data[body : body + 16])
            fmt = (audio_format, channels, sample_rate, bits)
        elif chunk_id == b"data":
            pcm_length = min(size, len(data) - body)
            break
        offset = body + size + (size & 1)
    if fmt is None or pcm_length is None:
        raise InvalidClip("WAV file has no fmt or data chunk.")
    audio_format, channels, sample_rate, bits = fmt
    if audio_format != 1 or channels != 1 or bits != 16:
        raise InvalidClip("Audio must be 16-bit PCM mono.")
    if not 8_000 <= sample_rate <= 48_000:
        raise InvalidClip("Unsupported sample rate.")
    duration = pcm_length / (sample_rate * 2)
    if duration < 1.0:
        raise InvalidClip("Clip is too short.")
    if duration > max_seconds + 0.5:
        raise InvalidClip(f"Clip is longer than {max_seconds:g} seconds.")
    return ClipInfo(wav_bytes=data, sample_rate=sample_rate, duration_seconds=duration)
