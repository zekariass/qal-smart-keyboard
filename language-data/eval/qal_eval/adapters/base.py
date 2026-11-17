"""Adapter interface. Implement the methods for the provider kinds you support."""
from __future__ import annotations

import os

REGISTRY: dict[str, type] = {}


def register(name: str):
    def deco(cls):
        cls.adapter_name = name
        REGISTRY[name] = cls
        return cls
    return deco


class AdapterError(Exception):
    """Raised for any provider failure. `timeout=True` for timeouts."""

    def __init__(self, message: str, timeout: bool = False):
        super().__init__(message)
        self.timeout = timeout


class Adapter:
    """Base class. `spec` is the provider entry from the config file.

    Common spec keys: name, kind, adapter, base_url, api_key_env, timeout_s, model, headers.
    The API key is read from the environment variable named by `api_key_env`; it is never
    stored in config.
    """

    def __init__(self, spec: dict):
        self.spec = spec
        self.name = spec["name"]
        self.kind = spec["kind"]
        self.base_url = spec.get("base_url", "").rstrip("/")
        self.timeout_s = float(spec.get("timeout_s", 10))
        self.model = spec.get("model", "")
        self.extra_headers = dict(spec.get("headers", {}))
        env = spec.get("api_key_env")
        self.api_key = os.environ.get(env, "") if env else ""
        self.last_model = ""  # model reported by the provider on the last call, if any

    def translate(self, text: str, source: str, target: str) -> str:
        """MT / LLM kinds: return the translated text, or raise AdapterError."""
        raise NotImplementedError

    def transcribe(self, audio_path: str, language: str) -> str:
        """ASR kind: return the transcript, or raise AdapterError."""
        raise NotImplementedError
