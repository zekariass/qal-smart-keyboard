import csv
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, ".."))

from qal_eval import adapters, cli  # noqa: E402
from qal_eval.adapters.base import AdapterError  # noqa: E402

SAMPLE = os.path.join(HERE, "..", "testsets", "sample")
ASR = os.path.join(HERE, "..", "testsets", "sample_asr", "audio", "silence.wav")


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        scen = self.headers.get("X-Mock-Scenario")
        if scen == "error":
            return self._send(503, {"error": {"code": "provider_unavailable"}})
        if scen == "slow":
            import time
            time.sleep(1.0)
        if self.path == "/ai/v1/translate":
            req = json.loads(body)
            return self._send(200, {"translation": "ሰላም " + req["text"], "model": "fake-mt"})
        if self.path == "/ai/v1/llm/chat":
            return self._send(200, {"output": "ሰላም", "model": "fake-llm"})
        if self.path == "/ai/v1/stt":
            assert b'name="language"' in body and b"RIFF" in body
            return self._send(200, {"transcript": "ሰላም እንዴት ነህ", "model": "fake-asr"})
        self._send(404, {})

    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class TestAdapter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = HTTPServer(("127.0.0.1", 0), Fake)
        cls.base = f"http://127.0.0.1:{cls.srv.server_port}/ai"
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def spec(self, kind="mt", **kw):
        return {"name": "t", "kind": kind, "adapter": "mock_http", "base_url": self.base, "timeout_s": 3, **kw}

    def test_translate(self):
        a = adapters.create(self.spec())
        self.assertEqual(a.translate("Hi", "en", "am"), "ሰላም Hi")
        self.assertEqual(a.last_model, "fake-mt")

    def test_llm(self):
        self.assertEqual(adapters.create(self.spec("llm")).translate("Hi", "en", "am"), "ሰላም")

    def test_asr(self):
        self.assertEqual(adapters.create(self.spec("asr")).transcribe(ASR, "am"), "ሰላም እንዴት ነህ")

    def test_error_and_timeout(self):
        with self.assertRaises(AdapterError) as c:
            adapters.create(self.spec(headers={"X-Mock-Scenario": "error"})).translate("x", "en", "am")
        self.assertFalse(c.exception.timeout)
        with self.assertRaises(AdapterError) as c:
            adapters.create(self.spec(timeout_s=0.2, headers={"X-Mock-Scenario": "slow"})).translate("x", "en", "am")
        self.assertTrue(c.exception.timeout)

    def test_connection_refused_is_adapter_error(self):
        with self.assertRaises(AdapterError):
            adapters.create(self.spec(base_url="http://127.0.0.1:1/ai")).translate("x", "en", "am")

    def test_unknown_adapter(self):
        with self.assertRaises(ValueError):
            adapters.create({"name": "x", "kind": "mt", "adapter": "nope"})


class TestCli(unittest.TestCase):
    def write_cfg(self, d, providers, **extra):
        cfg = {"providers": providers, "testsets": [{"name": "sample", "path": SAMPLE, "task": "translation"}], **extra}
        p = os.path.join(d, "c.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
        return p

    def test_end_to_end_and_errors_not_fatal(self):
        srv = HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{srv.server_port}/ai"
        try:
            with tempfile.TemporaryDirectory() as d:
                provs = [
                    {"name": "ok", "kind": "mt", "adapter": "mock_http", "base_url": base},
                    {"name": "bad", "kind": "mt", "adapter": "mock_http", "base_url": base,
                     "headers": {"X-Mock-Scenario": "error"}},
                ]
                cfgp = self.write_cfg(d, provs, language_pairs=["en-am"])
                out = os.path.join(d, "o.csv")
                items = os.path.join(d, "i.jsonl")
                rc = cli.main(["run", "--config", cfgp, "--out", out, "--items-out", items,
                               "--timestamp", "2026-01-01T00:00:00Z"])
                self.assertEqual(rc, 0)
                with open(out, encoding="utf-8") as f:
                    rows = list(csv.DictReader(f))
                self.assertEqual([r["provider"] for r in rows], ["ok", "bad"])
                self.assertEqual(rows[0]["n"], "5")
                self.assertEqual(rows[0]["errors"], "0")
                self.assertTrue(0 < float(rows[0]["script_ok_rate"]) < 1)  # fake echoes Latin source
                self.assertEqual(rows[1]["errors"], "5")
                self.assertEqual(rows[1]["chrF"], "")
                self.assertTrue(os.path.getsize(items) > 0)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_config_error_exit_code(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "bad.json")
            open(p, "w").write("{not json")
            self.assertEqual(cli.main(["run", "--config", p, "--out", os.path.join(d, "o.csv")]), 2)
            p2 = self.write_cfg(d, [{"name": "x", "kind": "mt", "adapter": "mock_http", "api_key": "secret"}])
            self.assertEqual(cli.main(["run", "--config", p2, "--out", os.path.join(d, "o.csv")]), 2)


if __name__ == "__main__":
    unittest.main()
