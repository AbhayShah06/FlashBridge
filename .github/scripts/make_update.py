import hashlib
import json
import os
import re
from pathlib import Path

tag = os.environ["RELEASE_TAG"]
root = Path("release")
gradle = Path("android/build.gradle").read_text(encoding="utf-8")
version_code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
version_name = re.search(r"versionName\s+'([^']+)'", gradle).group(1)
assert tag == "v" + version_name, "Tag and app version disagree"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


base = f"https://github.com/AbhayShah06/FlashBridge/releases/download/{tag}"
manifest = {
    "versionCode": version_code,
    "versionName": version_name,
    "apkUrl": base + "/FlashBridge.apk",
    "apkSha256": sha256(root / "FlashBridge.apk"),
    "windowsUrl": base + "/FlashBridge-Setup.exe",
    "windowsSha256": sha256(root / "FlashBridge-Setup.exe"),
}
(root / "update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
