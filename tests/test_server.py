import http.client
import hashlib
import json
import socket
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

from windows.flashbridge import Handler, safe_name, unique_path


class TransferTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.server.state = SimpleNamespace(folder=self.folder, token="test-token", pin="123456", lock=threading.Lock(), pair_attempts={}, on_event=lambda msg: None)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def request(self, method, path, body=None, token="test-token"):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        conn.request(method, path, body=body, headers={"X-FlashBridge-Token": token})
        resp = conn.getresponse()
        result = resp.status, resp.read()
        conn.close()
        return result

    def test_pair_and_bidirectional_transfer_without_overwrite(self):
        status, body = self.request("POST", "/v1/pair", json.dumps({"pin": "123456"}), token="")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["token"], "test-token")
        self.assertEqual(self.request("GET", "/v1/files", token="wrong")[0], 401)
        payload = b"abc" * 300000
        self.assertEqual(self.request("PUT", "/v1/files?name=test.bin", payload)[0], 201)
        status, body = self.request("PUT", "/v1/files?name=test.bin", payload)
        self.assertEqual(status, 201)
        self.assertTrue(json.loads(body)["skipped"])
        self.assertEqual(self.request("PUT", "/v1/files?name=test.bin", b"changed")[0], 201)
        self.assertEqual((self.folder / "test.bin").read_bytes(), payload)
        self.assertEqual((self.folder / "test (2).bin").read_bytes(), b"changed")
        listing = json.loads(self.request("GET", "/v1/files")[1])["files"]
        self.assertEqual([item["name"] for item in listing], ["test (2).bin", "test.bin"])
        self.assertEqual(self.request("GET", "/v1/files/test.bin"), (200, payload))
        self.assertEqual(json.loads(self.request("GET", "/v1/hash/test.bin")[1])["sha256"], __import__("hashlib").sha256(payload).hexdigest())

    def test_interrupted_upload_is_not_published(self):
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=4)
        sock.sendall(b"PUT /v1/files?name=broken.bin HTTP/1.1\r\nHost: localhost\r\nX-FlashBridge-Token: test-token\r\nContent-Length: 1000\r\n\r\npartial")
        sock.shutdown(socket.SHUT_WR)
        sock.close()
        for _ in range(30):
            if not any((self.folder / ".flashbridge-partials").glob("*.part")):
                break
            time.sleep(.05)
        self.assertFalse((self.folder / "broken.bin").exists())

    def test_name_validation(self):
        self.assertEqual(safe_name("a.bin"), "a.bin")
        with self.assertRaises(ValueError):
            safe_name("../")

    def test_mock_phone_large_file_and_retry(self):
        # Exercise the wire protocol with a mock Android client, including retry
        # after a dropped upload. This checks final bytes, not just HTTP status.
        payload = bytes(range(256)) * (8 * 1024 * 1024 // 256)
        started = time.monotonic()
        status, body = self.request("PUT", "/v1/files?name=large.bin", payload)
        self.assertEqual(status, 201)
        self.assertFalse(json.loads(body).get("skipped", False))
        self.assertEqual(self.request("GET", "/v1/files/large.bin"), (200, payload))
        self.assertEqual(hashlib.sha256((self.folder / "large.bin").read_bytes()).digest(), hashlib.sha256(payload).digest())
        self.assertTrue(json.loads(self.request("PUT", "/v1/files?name=large.bin", payload)[1])["skipped"])
        self.assertEqual(len(list(self.folder.glob("large*.bin"))), 1)
        self.assertLess(time.monotonic() - started, 30)

    def test_pairing_rejects_bad_codes_and_limits_guesses(self):
        for _ in range(5):
            self.assertEqual(self.request("POST", "/v1/pair", json.dumps({"pin": "000000"}), token="")[0], 403)
        self.assertEqual(self.request("POST", "/v1/pair", json.dumps({"pin": "123456"}), token="")[0], 429)

    def test_missing_receive_folder_reports_error(self):
        self.folder.rmdir()
        status, body = self.request("GET", "/v1/files")
        self.assertEqual(status, 503)
        self.assertIn("receive folder is unavailable", json.loads(body)["error"])


if __name__ == "__main__":
    unittest.main()
