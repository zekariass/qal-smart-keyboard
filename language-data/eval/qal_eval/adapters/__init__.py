"""Adapter registry. One module per adapter; each registers itself with @register."""
from __future__ import annotations

import importlib
import pkgutil

from .base import Adapter, AdapterError, REGISTRY, register  # noqa: F401


def load_all() -> None:
    for m in pkgutil.iter_modules(__path__):
        if m.name != "base":
            importlib.import_module(f"{__name__}.{m.name}")


def create(spec: dict) -> Adapter:
    load_all()
    t = spec.get("adapter")
    if t not in REGISTRY:
        raise ValueError(f"unknown adapter {t!r}; known: {sorted(REGISTRY)}")
    return REGISTRY[t](spec)
