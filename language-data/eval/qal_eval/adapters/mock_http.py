"""Adapter for the local WireMock stack (infra/compose). Not a real vendor API.

mt  -> POST {base}/v1/translate   {"text","source","target"} -> {"translation"}
llm -> POST {base}/v1/llm/chat    {"messages":[...]}         -> {"output"}
asr -> POST {base}/v1/stt         multipart audio+language   -> {"transcript"}
Set spec "headers": {"X-Mock-Scenario": "error"|"slow"} to exercise failure paths.
"""
from __future__ import annotations

import json
import os
import socket
import urllib.error
import urllib.request
import uuid

from .base import Adapter, AdapterError, register

LANG_NAMES = {"am": "Amharic", "ti": "Tigrinya", "om": "Afaan Oromoo", "en": "English"}


@register("mock_http")
class MockHttpAdapter(Adapter):
    def _post(self, path: str, body: bytes, content_type: str) -> dict:
        headers = {"Content-Type": content_type, "Accept": "application/json"}
        headers.update(self.extra_headers)
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        req = urllib.request.Request(self.base_url + path, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise AdapterError(f"HTTP {e.code}") from None
        except (socket.timeout, TimeoutError):
            raise AdapterError("timeout", timeout=True) from None
        except urllib.error.URLError as e:
            if isinstance(e.reason, (socket.timeout, TimeoutError)):
                raise AdapterError("timeout", timeout=True) from None
            raise AdapterError(f"connection error: {e.reason}") from None
        except (ValueError, UnicodeDecodeError):
            raise AdapterError("invalid JSON response") from None
        if not isinstance(data, dict):
            raise AdapterError("unexpected response shape")
        self.last_model = data.get("model", "") or self.model
        return data

    def translate(self, text, source, target):
        if self.kind == "llm":
            prompt = (f"Translate from {LANG_NAMES.get(source, source)} to "
                      f"{LANG_NAMES.get(target, target)}. Output only the translation.\n"
                      f"<text>{text}</text>")
            body = {"messages": [{"role": "user", "content": prompt}]}
            path, key = "/v1/llm/chat", "output"
        else:
            body = {"text": text, "source": source, "target": target}
            path, key = "/v1/translate", "translation"
        if self.model:
            body["model"] = self.model
        data = self._post(path, json.dumps(body).encode("utf-8"), "application/json")
        if not isinstance(data.get(key), str):
            raise AdapterError(f"missing {key!r} in response")
        return data[key]

    def transcribe(self, audio_path, language):
        boundary = uuid.uuid4().hex
        try:
            with open(audio_path, "rb") as f:
                audio = f.read()
        except OSError as e:
            raise AdapterError(f"cannot read audio: {e}") from None
        parts = [
            f'--{boundary}\r\nContent-Disposition: form-data; name="language"\r\n\r\n{language}\r\n'.encode(),
            (f'--{boundary}\r\nContent-Disposition: form-data; name="audio"; '
             f'filename="{os.path.basename(audio_path)}"\r\nContent-Type: audio/wav\r\n\r\n').encode()
            + audio + b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
        data = self._post("/v1/stt", b"".join(parts), f"multipart/form-data; boundary={boundary}")
        if not isinstance(data.get("transcript"), str):
            raise AdapterError("missing 'transcript' in response")
        return data["transcript"]
