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
VERSION = (0, 1, 7)
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


def address_list():
    try:
        values = {x[4][0] for x in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
        return sorted(x for x in values if not x.startswith("127.") and not x.startswith("169.254."))
    except OSError:
        return []


def addresses():
    return ", ".join(address_list()) or "Connect to the same network"


def pairing_link(host, pin):
    return f"flashbridge://pair?host={urllib.parse.quote(host)}&pin={pin}"


def main():
    import customtkinter as ctk
    import qrcode
    from PIL import Image
    from tkinter import Canvas, filedialog

    ctk.set_appearance_mode("dark")
    ctk.set_widget_scaling(0.8)
    state = State()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    server.state = state
    stop = threading.Event()
    events = queue.Queue()
    state.on_event = events.put
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=advertise, args=(stop,), daemon=True).start()

    root = ctk.CTk(fg_color="#101316")
    root.title("FlashBridge")
    root.geometry("1100x780")
    root.minsize(1100, 720)
    icon = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent)) / "assets" / "flashbridge.ico"
    if icon.is_file():
        root.iconbitmap(default=str(icon))
    if os.name == "nt":
        try:
            import ctypes
            hwnd = ctypes.windll.user32.GetParent(root.winfo_id())
            enabled = ctypes.c_int(1)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(enabled), 4)
        except Exception:
            pass

    main_area = ctk.CTkFrame(root, fg_color="transparent", corner_radius=0)
    main_area.pack(fill="both", expand=True)
    scrollbar = ctk.CTkScrollbar(main_area, fg_color="#101316")
    scrollbar.pack(side="right", fill="y")
    canvas = Canvas(main_area, bg="#101316", highlightthickness=0,
                    yscrollcommand=scrollbar.set)
    canvas.pack(side="left", fill="both", expand=True)
    scrollbar.configure(command=canvas.yview)
    content = ctk.CTkFrame(canvas, fg_color="transparent")
    window_id = canvas.create_window((0, 0), window=content, anchor="nw")
    canvas.bind("<Configure>", lambda event: canvas.itemconfigure(window_id, width=event.width))
    content.bind("<Configure>", lambda event: canvas.configure(scrollregion=canvas.bbox("all")))
    root.bind("<MouseWheel>", lambda event: canvas.yview_scroll(-int(event.delta / 120), "units"))
    content.grid_columnconfigure(0, weight=1)

    header = ctk.CTkFrame(content, fg_color="transparent")
    header.grid(row=0, column=0, sticky="ew", padx=28, pady=(26, 20))
    brand = ctk.CTkFrame(header, width=50, height=50, corner_radius=15, fg_color="#C8F36C")
    brand.pack(side="left", padx=(0, 14))
    brand.pack_propagate(False)
    ctk.CTkLabel(brand, text="↔", font=ctk.CTkFont(size=29, weight="bold"), text_color="#111713").pack(expand=True)
    name_group = ctk.CTkFrame(header, fg_color="transparent")
    name_group.pack(side="left")
    ctk.CTkLabel(name_group, text="FlashBridge", font=ctk.CTkFont(size=26, weight="bold"), text_color="#F5F7F4").pack(anchor="w")
    ctk.CTkLabel(name_group, text="Fast local transfers", font=ctk.CTkFont(size=13), text_color="#96A2A0").pack(anchor="w")
    ctk.CTkLabel(header, text="WINDOWS  ·  v0.1.7", font=ctk.CTkFont(size=11, weight="bold"), text_color="#8C9995").pack(side="right", anchor="n", pady=10)

    update_data = [None]
    update_bar = ctk.CTkFrame(content, fg_color="#253326", corner_radius=14)
    update_bar.grid(row=1, column=0, sticky="ew", padx=28, pady=(0, 16))
    update_bar.grid_remove()
    update_label = ctk.CTkLabel(update_bar, text="", font=ctk.CTkFont(size=14, weight="bold"), text_color="#EAF9D1")
    update_label.pack(side="left", padx=16, pady=10)

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

    update_button = ctk.CTkButton(update_bar, text="Update now", width=115, height=34,
                                  fg_color="#C8F36C", hover_color="#DBF9A4", text_color="#111713",
                                  command=install_update)
    update_button.pack(side="right", padx=12, pady=8)

    connection = ctk.CTkFrame(content, width=1000, height=400, fg_color="#1B2023", corner_radius=18, border_width=1, border_color="#313B3D")
    connection.grid(row=2, column=0, sticky="w", padx=28)
    connection.pack_propagate(False)
    files = ctk.CTkFrame(content, width=1000, height=300, fg_color="#1B2023", corner_radius=18, border_width=1, border_color="#313B3D")
    files.grid(row=3, column=0, sticky="w", padx=28, pady=(16, 0))
    files.pack_propagate(False)
    ctk.CTkLabel(connection, text="01  CONNECT YOUR PHONE", font=ctk.CTkFont(size=12, weight="bold"), text_color="#C8F36C").pack(anchor="w", padx=22, pady=(20, 3))
    pairing_row = ctk.CTkFrame(connection, fg_color="transparent")
    pairing_row.pack(fill="x", padx=22, pady=(6, 20))
    left_pair = ctk.CTkFrame(pairing_row, fg_color="transparent")
    left_pair.pack(side="left", anchor="n", padx=(0, 22))
    right_pair = ctk.CTkFrame(pairing_row, width=430, height=350, fg_color="transparent")
    right_pair.pack(side="left", anchor="n")
    right_pair.pack_propagate(False)
    qr_label = ctk.CTkLabel(left_pair, text="", width=190, height=190, fg_color="#FFFFFF", corner_radius=10)
    qr_label.pack(anchor="n")
    ctk.CTkLabel(right_pair, text="Scan to pair", font=ctk.CTkFont(size=22, weight="bold"), text_color="#F5F7F4").pack(anchor="w")
    ctk.CTkLabel(right_pair, text="Open FlashBridge on Android and scan this code.", font=ctk.CTkFont(size=13), text_color="#A8B2AF", wraplength=330, justify="left").pack(anchor="w", pady=(2, 12))
    hosts = address_list()
    selected_host = ctk.StringVar(value=hosts[0] if hosts else "No network address")
    link_value = ctk.StringVar(value="")

    def update_qr(_=None):
        host = selected_host.get()
        if host == "No network address":
            qr_label.configure(image=None, text="Connect PC to a network")
            link_value.set("")
            return
        link_value.set(pairing_link(host, state.pin))
        qr = qrcode.QRCode(box_size=8, border=2, error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(pairing_link(host, state.pin))
        qr.make(fit=True)
        image = qr.make_image(fill_color="#10201B", back_color="#FFFFFF").get_image().convert("RGB")
        qr_label.configure(image=ctk.CTkImage(light_image=image, dark_image=image, size=(190, 190)), text="")

    update_qr()
    ctk.CTkLabel(right_pair, text="PC address", font=ctk.CTkFont(size=12), text_color="#A8B2AF").pack(anchor="w")
    host_menu = ctk.CTkOptionMenu(right_pair, values=hosts or ["No network address"], variable=selected_host,
                                  command=update_qr, height=34, fg_color="#303B3A", button_color="#40504A",
                                  button_hover_color="#526A58", text_color="#F5F7F4")
    host_menu.pack(fill="x", pady=(4, 10))
    ctk.CTkEntry(right_pair, textvariable=link_value, state="readonly", height=32,
                 fg_color="#13191B", border_color="#303B3A", text_color="#B7C6BD").pack(fill="x", pady=(0, 10))
    code_row = ctk.CTkFrame(right_pair, fg_color="transparent")
    code_row.pack(fill="x", pady=(0, 10))
    ctk.CTkLabel(code_row, text="Code", font=ctk.CTkFont(size=12), text_color="#A8B2AF").pack(side="left")
    ctk.CTkLabel(code_row, text=state.pin, font=ctk.CTkFont(size=17, weight="bold"), text_color="#F5F7F4").pack(side="right")

    def copy_link():
        host = selected_host.get()
        if host == "No network address":
            status.configure(text="Connect the PC to Wi-Fi or your phone's hotspot first.")
            return
        root.clipboard_clear()
        root.clipboard_append(pairing_link(host, state.pin))
        status.configure(text="Pairing link copied. Paste it in FlashBridge on Android.")

    ctk.CTkButton(right_pair, text="Copy pairing link", height=38, corner_radius=10,
                  fg_color="#303B3A", hover_color="#40504A", text_color="#F5F7F4",
                  command=copy_link).pack(fill="x", pady=(0, 8))
    ctk.CTkLabel(right_pair, text="QR pairing still needs reachable Wi-Fi or a hotspot.",
                 font=ctk.CTkFont(size=11), text_color="#8FA19A", wraplength=330,
                 justify="left").pack(anchor="w")

    ctk.CTkLabel(files, text="02  CHOOSE FILES", font=ctk.CTkFont(size=12, weight="bold"), text_color="#C8F36C").pack(anchor="w", padx=22, pady=(20, 3))
    ctk.CTkLabel(files, text="Ready to send", font=ctk.CTkFont(size=22, weight="bold"), text_color="#F5F7F4").pack(anchor="w", padx=22)
    ctk.CTkLabel(files, text="Add multiple files, then tap Get from PC on Android.",
                 font=ctk.CTkFont(size=13), text_color="#A8B2AF", wraplength=335,
                 justify="left").pack(anchor="w", padx=22, pady=(2, 22))
    ctk.CTkLabel(files, text="TRANSFER FOLDER", font=ctk.CTkFont(size=11, weight="bold"), text_color="#8FA19A").pack(anchor="w", padx=22)
    folder_text = ctk.CTkLabel(files, text=str(state.folder), font=ctk.CTkFont(size=13),
                               text_color="#E2E9E4", wraplength=330, justify="left")
    folder_text.pack(anchor="w", padx=22, pady=(5, 18))

    def choose_folder():
        chosen = filedialog.askdirectory(initialdir=str(state.folder), title="Choose transfer folder")
        if chosen:
            with state.lock:
                state.folder = Path(chosen)
                state.save()
            folder_text.configure(text=str(state.folder))

    def add_file():
        chosen = filedialog.askopenfilenames(title="Choose files for Android")
        if chosen:
            folder = state.folder
            def copy_selected():
                added, skipped, failed, last_error = add_files(chosen, folder, events.put, state.lock)
                events.put(f"Ready for Android: {added} added, {skipped} already there, {failed} failed."
                           + (f" {last_error}" if failed else " Tap Get from PC on Android."))
            threading.Thread(target=copy_selected, daemon=True).start()

    ctk.CTkButton(files, text="Add files", command=add_file, height=48, corner_radius=11,
                  fg_color="#C8F36C", hover_color="#DBF9A4", text_color="#111713",
                  font=ctk.CTkFont(size=15, weight="bold")).pack(fill="x", padx=22, pady=(0, 10))
    ctk.CTkButton(files, text="Change folder", command=choose_folder, height=40, corner_radius=10,
                  fg_color="#303B3A", hover_color="#40504A", text_color="#F5F7F4").pack(fill="x", padx=22, pady=(0, 20))

    activity = ctk.CTkFrame(content, width=1000, height=95, fg_color="#1B2023", corner_radius=18, border_width=1, border_color="#313B3D")
    activity.grid(row=4, column=0, sticky="w", padx=28, pady=(16, 24))
    activity.pack_propagate(False)
    ctk.CTkLabel(activity, text="ACTIVITY", font=ctk.CTkFont(size=12, weight="bold"), text_color="#C8F36C").pack(anchor="w", padx=22, pady=(16, 2))
    status = ctk.CTkLabel(activity, text="Waiting for Android. Keep this app open during transfers.",
                          font=ctk.CTkFont(size=14), text_color="#E2E9E4", wraplength=740, justify="left")
    status.pack(anchor="w", padx=22, pady=(0, 18))

    def close():
        stop.set()
        server.shutdown()
        server.server_close()
        root.destroy()

    def show_events():
        while not events.empty():
            event = events.get_nowait()
            if isinstance(event, tuple) and event[0] == "update":
                update_data[0] = event[1]
                update_label.configure(text=f"Update {event[1]['versionName']} is ready")
                update_bar.grid()
            elif isinstance(event, tuple) and event[0] == "install":
                try:
                    subprocess.Popen([event[1]], cwd=str(Path(event[1]).parent))
                    close()
                    return
                except OSError as error:
                    status.configure(text="Couldn't start the installer. " + friendly_error(error))
                    update_button.configure(state="normal", text="Retry update")
            elif isinstance(event, tuple) and event[0] == "error":
                status.configure(text=event[1])
                update_button.configure(state="normal", text="Retry update")
            else:
                status.configure(text=str(event))
        root.after(200, show_events)

    def refresh_addresses():
        current = address_list()
        options = current or ["No network address"]
        if list(host_menu.cget("values")) != options:
            host_menu.configure(values=options)
            if selected_host.get() not in options:
                selected_host.set(options[0])
                update_qr()
        root.after(2500, refresh_addresses)

    def look_for_update():
        try:
            latest = check_update()
            if latest is not None:
                events.put(("update", latest))
        except Exception:
            pass

    root.protocol("WM_DELETE_WINDOW", close)
    root.after(200, show_events)
    root.after(2500, refresh_addresses)
    threading.Thread(target=look_for_update, daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    main()
