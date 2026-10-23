"""Local file receiver and sender for FlashBridge. Standard library only."""
import json
import os
import queue
import secrets
import shutil
import socket
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = 48888
DISCOVERY_PORT = 48889
MAGIC = b"FLASHBRIDGE_DISCOVER_V1"
APP_DIR = Path(os.getenv("APPDATA", str(Path.home()))) / "FlashBridge"
CONFIG_PATH = APP_DIR / "config.json"
BUF = 256 * 1024
MAX_FILE = 1 << 45


def safe_name(value):
    name = value.replace("\\", "/").split("/")[-1].strip().rstrip(". ")
    stem = name.split(".")[0].upper()
    if (not name or name in (".", "..") or stem in {"CON", "PRN", "AUX", "NUL"}
            or (len(stem) == 4 and stem[:3] in {"COM", "LPT"} and stem[3].isdigit())
            or any(ord(c) < 32 or c in '<>:"|?*' for c in name)):
        raise ValueError("Invalid file name")
    return name[:240]


def unique_path(folder, name):
    path = folder / name
    stem, ext = os.path.splitext(name)
    index = 2
    while path.exists():
        path = folder / f"{stem} ({index}){ext}"
        index += 1
    return path


class State:
    def __init__(self):
        APP_DIR.mkdir(parents=True, exist_ok=True)
        try:
            saved = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        self.folder = Path(saved.get("folder") or (Path.home() / "Documents" / "FlashBridge"))
        self.folder.mkdir(parents=True, exist_ok=True)
        self.token = saved.get("token") or secrets.token_urlsafe(32)
        self.pin = f"{secrets.randbelow(1000000):06d}"
        self.lock = threading.Lock()
        self.pair_attempts = {}
        self.on_event = lambda msg: None
        self.save()

    def save(self):
        temp = CONFIG_PATH.with_suffix(".tmp")
        temp.write_text(json.dumps({"folder": str(self.folder), "token": self.token}), encoding="utf-8")
        temp.replace(CONFIG_PATH)


class Handler(BaseHTTPRequestHandler):
    server_version = "FlashBridge/0.1"
    protocol_version = "HTTP/1.1"

    @property
    def state(self):
        return self.server.state

    def log_message(self, *_):
        pass

    def respond(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authenticated(self):
        if not secrets.compare_digest(self.headers.get("X-FlashBridge-Token", ""), self.state.token):
            self.respond(401, {"error": "Pair this device first"})
            return False
        return True

    def do_POST(self):
        if self.path != "/v1/pair":
            return self.respond(404, {"error": "Not found"})
        ip = self.client_address[0]
        with self.state.lock:
            attempts = [t for t in self.state.pair_attempts.get(ip, []) if time.time() - t < 60]
            if len(attempts) >= 5:
                self.state.pair_attempts[ip] = attempts
                return self.respond(429, {"error": "Too many attempts; wait a minute"})
            attempts.append(time.time())
            self.state.pair_attempts[ip] = attempts
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 256:
                return self.respond(400, {"error": "Invalid request"})
            entered = json.loads(self.rfile.read(length))["pin"]
            if not secrets.compare_digest(str(entered), self.state.pin):
                return self.respond(403, {"error": "Wrong code"})
            self.state.on_event("Android paired")
            self.respond(200, {"token": self.state.token})
        except (ValueError, KeyError, TypeError):
            self.respond(400, {"error": "Invalid request"})

    def do_GET(self):
        if not self.authenticated():
            return
        path = urllib.parse.urlsplit(self.path).path
        if path == "/v1/files":
            files = [{"name": p.name, "size": p.stat().st_size} for p in self.state.folder.iterdir() if p.is_file() and not p.name.startswith(".")]
            files.sort(key=lambda x: x["name"].casefold())
            return self.respond(200, {"files": files})
        if not path.startswith("/v1/files/"):
            return self.respond(404, {"error": "Not found"})
        try:
            name = safe_name(urllib.parse.unquote(path[len("/v1/files/"):]))
            if urllib.parse.unquote(path[len("/v1/files/"):]) != name:
                raise ValueError()
            source = self.state.folder / name
            if not source.is_file() or source.is_symlink() or name.startswith("."):
                return self.respond(404, {"error": "File not found"})
            size = source.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with source.open("rb") as handle:
                while chunk := handle.read(BUF):
                    self.wfile.write(chunk)
            self.state.on_event(f"Sent {name}")
        except (ValueError, OSError, BrokenPipeError):
            pass

    def do_PUT(self):
        if not self.authenticated():
            return
        path = urllib.parse.urlsplit(self.path)
        if path.path != "/v1/files":
            return self.respond(404, {"error": "Not found"})
        try:
            name = safe_name(urllib.parse.parse_qs(path.query)["name"][0])
            size = int(self.headers.get("Content-Length", "-1"))
            if size < 0 or size > MAX_FILE:
                return self.respond(400, {"error": "Invalid file size"})
            temp_dir = self.state.folder / ".flashbridge-partials"
            temp_dir.mkdir(exist_ok=True)
            temporary = temp_dir / (secrets.token_hex(16) + ".part")
            remaining = size
            with temporary.open("xb") as output:
                while remaining:
                    chunk = self.rfile.read(min(BUF, remaining))
                    if not chunk:
                        raise ConnectionError("Transfer interrupted")
                    output.write(chunk)
                    remaining -= len(chunk)
                output.flush()
                os.fsync(output.fileno())
            with self.state.lock:
                destination = unique_path(self.state.folder, name)
                temporary.rename(destination)
            self.state.on_event(f"Received {destination.name}")
            self.respond(201, {"name": destination.name, "size": size})
        except (ValueError, KeyError, IndexError):
            self.respond(400, {"error": "Invalid upload"})
        except (OSError, ConnectionError) as exc:
            self.close_connection = True
            try:
                self.respond(500, {"error": str(exc)})
            except OSError:
                pass
        finally:
            if "temporary" in locals():
                temporary.unlink(missing_ok=True)


def advertise(stop):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        while not stop.wait(2):
            try:
                sock.sendto(MAGIC + b"|" + str(PORT).encode(), ("255.255.255.255", DISCOVERY_PORT))
            except OSError:
                pass
    finally:
        sock.close()


def addresses():
    try:
        values = {x[4][0] for x in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
        return ", ".join(sorted(x for x in values if not x.startswith("127."))) or "Connect to the same network"
    except OSError:
        return "Connect to the same network"


def main():
    from tkinter import Tk, StringVar, filedialog, messagebox, ttk
    state = State()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    server.state = state
    stop = threading.Event()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=advertise, args=(stop,), daemon=True).start()

    root = Tk()
    events = queue.Queue()
    root.title("FlashBridge")
    root.geometry("510x340")
    root.minsize(460, 300)
    frame = ttk.Frame(root, padding=20)
    frame.pack(fill="both", expand=True)
    ttk.Label(frame, text="FlashBridge", font=("Segoe UI", 20, "bold")).pack(anchor="w")
    ttk.Label(frame, text="Android ↔ Windows · local file transfer").pack(anchor="w", pady=(0, 18))
    ttk.Label(frame, text="Pairing code", font=("Segoe UI", 10, "bold")).pack(anchor="w")
    ttk.Label(frame, text=state.pin, font=("Segoe UI", 26, "bold")).pack(anchor="w")
    ttk.Label(frame, text=f"PC address: {addresses()}  ·  port {PORT}").pack(anchor="w", pady=(0, 12))
    folder_text = StringVar(value=f"Folder: {state.folder}")
    ttk.Label(frame, textvariable=folder_text, wraplength=460).pack(anchor="w")

    def choose_folder():
        chosen = filedialog.askdirectory(initialdir=str(state.folder), title="Choose transfer folder")
        if chosen:
            with state.lock:
                state.folder = Path(chosen)
                state.save()
            folder_text.set(f"Folder: {state.folder}")

    def add_file():
        chosen = filedialog.askopenfilename(title="Make file available to Android")
        if chosen:
            try:
                source = Path(chosen)
                with state.lock:
                    destination = unique_path(state.folder, source.name)
                    temp_dir = state.folder / ".flashbridge-partials"
                    temp_dir.mkdir(exist_ok=True)
                    temporary = temp_dir / (secrets.token_hex(16) + ".part")
                    try:
                        shutil.copy2(source, temporary)
                        temporary.rename(destination)
                    finally:
                        temporary.unlink(missing_ok=True)
                messagebox.showinfo("Ready", f"Added {destination.name}. On Android, tap Get from PC.")
            except OSError as exc:
                messagebox.showerror("Could not add file", str(exc))

    row = ttk.Frame(frame)
    row.pack(anchor="w", pady=12)
    ttk.Button(row, text="Choose folder", command=choose_folder).pack(side="left", padx=(0, 8))
    ttk.Button(row, text="Add file to folder", command=add_file).pack(side="left")
    status = StringVar(value="Waiting for Android. Keep this app open during transfers.")
    ttk.Label(frame, textvariable=status, wraplength=460).pack(anchor="w", pady=(8, 0))
    state.on_event = events.put

    def show_events():
        while not events.empty():
            status.set(events.get_nowait())
        root.after(200, show_events)

    root.after(200, show_events)

    def close():
        stop.set()
        server.shutdown()
        server.server_close()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.mainloop()


if __name__ == "__main__":
    main()
