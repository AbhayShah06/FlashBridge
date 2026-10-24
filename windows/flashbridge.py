"""Local file receiver and sender for FlashBridge. Standard library only."""
import json
import errno
import hashlib
import os
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = 48888
DISCOVERY_PORT = 48889
MAGIC = b"FLASHBRIDGE_DISCOVER_V1"
APP_DIR = Path(os.getenv("APPDATA", str(Path.home()))) / "FlashBridge"
CONFIG_PATH = APP_DIR / "config.json"
BUF = 256 * 1024
MAX_FILE = 1 << 45
VERSION = (0, 1, 3)
UPDATE_MANIFEST = "https://github.com/AbhayShah06/FlashBridge/releases/latest/download/update.json"


def friendly_error(error):
    if isinstance(error, OSError):
        if error.errno == errno.ENOSPC:
            return "Storage is full. Free up space in the transfer folder and try again."
        if error.errno in (errno.EACCES, errno.EPERM):
            return "File access denied. Choose another transfer folder or check its permissions."
        if error.errno == errno.ENOENT:
            return "A selected file or transfer folder is missing. Choose it again."
    if isinstance(error, ValueError) and "checksum" in str(error).lower():
        return "Update download was damaged. Try updating again."
    return "That didn't work. Try again."


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


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(BUF):
            digest.update(chunk)
    return digest.hexdigest()


def add_files(paths, folder, progress, lock):
    """Copy a selected batch into the shared folder without replacing finished files."""
    added = skipped = failed = 0
    last_error = ""
    for index, selected in enumerate(paths, 1):
        source = Path(selected)
        temporary = None
        try:
            size = source.stat().st_size
            temp_dir = folder / ".flashbridge-partials"
            temp_dir.mkdir(exist_ok=True)
            temporary = temp_dir / (secrets.token_hex(16) + ".part")
            digest = hashlib.sha256()
            done, last = 0, -1
            last_update = 0.0
            with keep_awake(), source.open("rb") as input_file, temporary.open("xb") as output:
                while chunk := input_file.read(BUF):
                    output.write(chunk)
                    digest.update(chunk)
                    done += len(chunk)
                    percent = int(done * 100 / size) if size else 100
                    now = time.monotonic()
                    if percent != last and (now - last_update >= .2 or percent == 100):
                        last = percent
                        last_update = now
                        progress(f"Adding {index}/{len(paths)}: {source.name}… {percent}%")
                output.flush()
                os.fsync(output.fileno())
            with lock:
                existing = folder / source.name
                if existing.is_file() and existing.stat().st_size == size and file_hash(existing) == digest.hexdigest():
                    skipped += 1
                    continue
                destination = unique_path(folder, source.name)
                temporary.rename(destination)
                added += 1
        except OSError as error:
            failed += 1
            last_error = friendly_error(error)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    return added, skipped, failed, last_error


def check_update():
    request = urllib.request.Request(UPDATE_MANIFEST, headers={"User-Agent": "FlashBridge/0.1"})
    with urllib.request.urlopen(request, timeout=6) as response:
        manifest = json.load(response)
    version = tuple(int(value) for value in manifest["versionName"].split("."))
    if version > VERSION:
        url = manifest["windowsUrl"]
        digest = manifest["windowsSha256"]
        if not url.startswith("https://github.com/AbhayShah06/FlashBridge/releases/download/") or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
            raise ValueError("Invalid update manifest")
        return manifest
    return None


def download_update(manifest, progress):
    folder = APP_DIR / "updates"
    folder.mkdir(parents=True, exist_ok=True)
    temporary = folder / "FlashBridge-Setup.exe.part"
    installer = folder / "FlashBridge-Setup.exe"
    digest = hashlib.sha256()
    request = urllib.request.Request(manifest["windowsUrl"], headers={"User-Agent": "FlashBridge/0.1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response, temporary.open("wb") as output:
            total = int(response.headers.get("Content-Length", "0"))
            done, last = 0, -1
            while chunk := response.read(BUF):
                output.write(chunk)
                digest.update(chunk)
                done += len(chunk)
                percent = int(done * 100 / total) if total else 0
                if percent != last:
                    last = percent
                    progress(f"Downloading update… {percent}%")
            output.flush()
            os.fsync(output.fileno())
        if digest.hexdigest().lower() != manifest["windowsSha256"].lower():
            raise ValueError("Update checksum mismatch")
        temporary.replace(installer)
        return installer
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def keep_awake():
    if os.name == "nt":
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
    try:
        yield
    finally:
        if os.name == "nt":
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)


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
            folder = self.state.folder
            if not folder.is_dir():
                return self.respond(503, {"error": "PC receive folder is unavailable; choose another folder in FlashBridge"})
            try:
                files = [{"name": p.name, "size": p.stat().st_size} for p in folder.iterdir() if p.is_file() and not p.name.startswith(".")]
            except OSError:
                return self.respond(503, {"error": "PC receive folder is unavailable; choose another folder in FlashBridge"})
            files.sort(key=lambda x: x["name"].casefold())
            return self.respond(200, {"files": files})
        if path.startswith("/v1/hash/"):
            try:
                raw = urllib.parse.unquote(path[len("/v1/hash/"):])
                name = safe_name(raw)
                if name != raw or name.startswith("."):
                    raise ValueError()
                source = self.state.folder / name
                if not source.is_file() or source.is_symlink():
                    return self.respond(404, {"error": "File not found"})
                return self.respond(200, {"sha256": file_hash(source), "size": source.stat().st_size})
            except (ValueError, OSError):
                return self.respond(400, {"error": "Invalid file"})
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
            done, last = 0, -1
            with keep_awake(), source.open("rb") as handle:
                while chunk := handle.read(BUF):
                    self.wfile.write(chunk)
                    done += len(chunk)
                    percent = int(done * 100 / size) if size else 100
                    if percent != last:
                        last = percent
                        self.state.on_event(f"Sending {name}… {percent}%")
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
            folder = self.state.folder
            temp_dir = folder / ".flashbridge-partials"
            temp_dir.mkdir(exist_ok=True)
            temporary = temp_dir / (secrets.token_hex(16) + ".part")
            remaining = size
            digest = hashlib.sha256()
            last = -1
            with keep_awake(), temporary.open("xb") as output:
                while remaining:
                    chunk = self.rfile.read(min(BUF, remaining))
                    if not chunk:
                        raise ConnectionError("Transfer interrupted")
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                    percent = int((size - remaining) * 100 / size) if size else 100
                    if percent != last:
                        last = percent
                        self.state.on_event(f"Receiving {name}… {percent}%")
                output.flush()
                os.fsync(output.fileno())
            with self.state.lock:
                existing = folder / name
                if existing.is_file() and not existing.is_symlink() and existing.stat().st_size == size and file_hash(existing) == digest.hexdigest():
                    self.state.on_event(f"Already have {name}")
                    return self.respond(201, {"name": name, "size": size, "skipped": True})
                destination = unique_path(folder, name)
                temporary.rename(destination)
            self.state.on_event(f"Received {destination.name}")
            self.respond(201, {"name": destination.name, "size": size})
        except (ValueError, KeyError, IndexError):
            self.respond(400, {"error": "Invalid upload"})
        except (OSError, ConnectionError) as exc:
            self.close_connection = True
            self.state.on_event(f"Transfer stopped: {friendly_error(exc)}")
            try:
                self.respond(507 if isinstance(exc, OSError) and exc.errno == errno.ENOSPC else 500,
                             {"error": friendly_error(exc)})
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
    icon = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent)) / "assets" / "flashbridge.ico"
    if icon.is_file():
        root.iconbitmap(default=str(icon))
    events = queue.Queue()
    root.title("FlashBridge")
    root.geometry("550x500")
    root.minsize(490, 420)
    root.configure(bg="#111214")
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure("TFrame", background="#111214")
    style.configure("Card.TFrame", background="#1D1F22")
    style.configure("TLabel", background="#111214", foreground="#F4F5F2", font=("Segoe UI", 10))
    style.configure("Card.TLabel", background="#1D1F22", foreground="#F4F5F2", font=("Segoe UI", 10))
    style.configure("Muted.TLabel", background="#111214", foreground="#A7ABB0", font=("Segoe UI", 10))
    style.configure("CardMuted.TLabel", background="#1D1F22", foreground="#A7ABB0", font=("Segoe UI", 10))
    style.configure("Eyebrow.TLabel", background="#111214", foreground="#BEF264", font=("Segoe UI", 9, "bold"))
    style.configure("Section.TLabel", background="#1D1F22", foreground="#BEF264", font=("Segoe UI", 9, "bold"))
    style.configure("Title.TLabel", background="#111214", foreground="#F4F5F2", font=("Segoe UI", 23, "bold"))
    style.configure("Code.TLabel", background="#1D1F22", foreground="#BEF264", font=("Segoe UI", 27, "bold"))
    style.configure("TButton", background="#BEF264", foreground="#111214", borderwidth=0, padding=(14, 10), font=("Segoe UI", 10, "bold"))
    style.map("TButton", background=[("active", "#D5FC90"), ("disabled", "#50584B")])
    style.configure("Secondary.TButton", background="#2B2E31", foreground="#F4F5F2")
    style.map("Secondary.TButton", background=[("active", "#3A3E42")])
    frame = ttk.Frame(root, padding=22)
    frame.pack(fill="both", expand=True)
    ttk.Label(frame, text="LOCAL TRANSFER", style="Eyebrow.TLabel").pack(anchor="w")
    ttk.Label(frame, text="FlashBridge", style="Title.TLabel").pack(anchor="w")
    ttk.Label(frame, text="Your files, straight between devices.", style="Muted.TLabel").pack(anchor="w")
    update_label = ttk.Label(frame, text="")
    update_button = ttk.Button(frame, text="Update now")
    update_data = [None]
    connection_card = ttk.Frame(frame, style="Card.TFrame", padding=16)
    connection_card.pack(fill="x", pady=(18, 8))
    ttk.Label(connection_card, text="CONNECTION", style="Section.TLabel").pack(anchor="w")
    ttk.Label(connection_card, text="Pairing code", style="CardMuted.TLabel").pack(anchor="w", pady=(7, 0))
    ttk.Label(connection_card, text=state.pin, style="Code.TLabel").pack(anchor="w")
    address_text = StringVar(value=f"PC address: {addresses()}  ·  port {PORT}")
    ttk.Label(connection_card, textvariable=address_text, style="CardMuted.TLabel").pack(anchor="w")
    files_card = ttk.Frame(frame, style="Card.TFrame", padding=16)
    files_card.pack(fill="x", pady=(8, 8))
    ttk.Label(files_card, text="FILES", style="Section.TLabel").pack(anchor="w")
    folder_text = StringVar(value=f"Folder: {state.folder}")
    folder_text_label = ttk.Label(files_card, textvariable=folder_text, style="CardMuted.TLabel", wraplength=460)
    folder_text_label.pack(anchor="w")

    def choose_folder():
        chosen = filedialog.askdirectory(initialdir=str(state.folder), title="Choose transfer folder")
        if chosen:
            with state.lock:
                state.folder = Path(chosen)
                state.save()
            folder_text.set(f"Folder: {state.folder}")

    def add_file():
        chosen = filedialog.askopenfilenames(title="Choose files for Android")
        if chosen:
            folder = state.folder
            def copy_selected():
                added, skipped, failed, last_error = add_files(chosen, folder, events.put, state.lock)
                events.put(f"Ready for Android: {added} added, {skipped} already there, {failed} failed."
                           + (f" Last error: {last_error}" if failed else " Tap Get from PC on Android."))
            threading.Thread(target=copy_selected, daemon=True).start()

    row = ttk.Frame(files_card, style="Card.TFrame")
    row.pack(anchor="w", pady=12)
    ttk.Button(row, text="Choose folder", style="Secondary.TButton", command=choose_folder).pack(side="left", padx=(0, 8))
    ttk.Button(row, text="Add files to folder", command=add_file).pack(side="left")
    status = StringVar(value="Waiting for Android. Keep this app open during transfers.")
    progress_card = ttk.Frame(frame, style="Card.TFrame", padding=16)
    progress_card.pack(fill="x", pady=(8, 0))
    ttk.Label(progress_card, text="TRANSFER", style="Section.TLabel").pack(anchor="w")
    ttk.Label(progress_card, textvariable=status, style="Card.TLabel", wraplength=460).pack(anchor="w", pady=(5, 0))
    state.on_event = events.put

    def install_update():
        if update_data[0] is None:
            return
        update_button.configure(state="disabled", text="Downloading…")
        def work():
            try:
                installer = download_update(update_data[0], events.put)
                events.put(("install", str(installer)))
            except Exception as error:
                events.put(("error", friendly_error(error)))
        threading.Thread(target=work, daemon=True).start()

    update_button.configure(command=install_update)

    def show_events():
        while not events.empty():
            event = events.get_nowait()
            if isinstance(event, tuple) and event[0] == "update":
                update_data[0] = event[1]
                update_label.configure(text=f"Update {event[1]['versionName']} available")
                update_label.pack(anchor="w", before=connection_card)
                update_button.pack(anchor="w", before=connection_card)
            elif isinstance(event, tuple) and event[0] == "install":
                try:
                    subprocess.Popen([event[1]], cwd=str(Path(event[1]).parent))
                    close()
                    return
                except OSError as error:
                    status.set("Couldn't start the update installer. " + friendly_error(error))
                    update_button.configure(state="normal", text="Retry update")
            elif isinstance(event, tuple) and event[0] == "error":
                status.set(event[1])
                update_button.configure(state="normal", text="Retry update")
            elif isinstance(event, tuple) and event[0] == "ready":
                status.set(event[1])
                messagebox.showinfo("Ready", event[1])
            else:
                status.set(event)
        root.after(200, show_events)

    root.after(200, show_events)

    def refresh_address():
        address_text.set(f"PC address: {addresses()}  ·  port {PORT}")
        root.after(2500, refresh_address)

    root.after(2500, refresh_address)

    def look_for_update():
        try:
            latest = check_update()
            if latest is not None:
                events.put(("update", latest))
        except Exception:
            pass

    threading.Thread(target=look_for_update, daemon=True).start()

    def close():
        stop.set()
        server.shutdown()
        server.server_close()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.mainloop()


if __name__ == "__main__":
    main()
