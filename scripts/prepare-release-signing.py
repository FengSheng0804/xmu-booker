"""Materialize Actions signing secrets only in the runner's private temporary directory."""
import argparse
import base64
import os
from pathlib import Path
import shutil

NAMES = ("XMU_ANDROID_KEYSTORE_BASE64", "XMU_ANDROID_STORE_PASSWORD",
         "XMU_ANDROID_KEY_ALIAS", "XMU_ANDROID_KEY_PASSWORD")


def property_value(value):
    # java.util.Properties.load(InputStream) uses Latin-1 and Java unicode escapes.
    escaped = []
    for character in value:
        if character in "\\=:#! ":
            escaped.append("\\" + character)
        elif character in "\n\r\t\f":
            escaped.append({"\n": "\\n", "\r": "\\r", "\t": "\\t", "\f": "\\f"}[character])
        elif not 32 <= ord(character) <= 126:
            encoded = character.encode("utf-16-be")
            escaped.extend("\\u" + encoded[i:i + 2].hex() for i in range(0, len(encoded), 2))
        else:
            escaped.append(character)
    return "".join(escaped)


def main(clean=False):
    runner = os.environ.get("RUNNER_TEMP")
    if not runner:
        raise SystemExit("RUNNER_TEMP is required")
    root = Path(runner).resolve()
    directory = root / "xmu-release-signing"
    if directory.is_symlink() or directory.resolve().parent != root:
        raise SystemExit("Unsafe signing temporary directory")
    if clean:
        if directory.exists():
            shutil.rmtree(directory)
        return
    missing = [name for name in NAMES if not os.environ.get(name)]
    if missing:
        raise SystemExit("Configure repository Actions secrets: " + ", ".join(missing))
    try:
        key = base64.b64decode(os.environ[NAMES[0]], validate=True)
        if not key:
            raise ValueError()
    except ValueError:
        raise SystemExit("Invalid XMU_ANDROID_KEYSTORE_BASE64") from None
    directory.mkdir(mode=0o700, exist_ok=False)
    store = directory / "release.p12"
    store.write_bytes(key)
    store.chmod(0o600)
    properties = directory / "signing.properties"
    fields = dict(storeFile=str(store), storePassword=os.environ[NAMES[1]],
                  keyAlias=os.environ[NAMES[2]], keyPassword=os.environ[NAMES[3]])
    properties.write_text("".join(f"{k}={property_value(v)}\n" for k, v in fields.items()), encoding="ascii")
    properties.chmod(0o600)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write(f"XMU_ANDROID_KEY_PROPERTIES={properties}\n")
    print("Prepared private release signing configuration")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true")
    main(parser.parse_args().clean)
