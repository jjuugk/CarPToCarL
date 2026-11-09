"""Prepare the pinned standalone relay assets for the Car+ runtime APK."""

import argparse
import hashlib
import lzma
import shutil
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

SHA256 = "97aac7e5bbf4a90156b9c89bb3bc9cefb7e837676f1047270cce34d9e4c97b0f"
SOURCE = Path(__file__).resolve().parent


def prepare(sdk, javac, compiler, injector, copying, destination):
    with injector.open("rb") as archive:
        if hashlib.file_digest(archive, "sha256").hexdigest() != SHA256:
            raise ValueError("Unexpected Frida 17.26.2 Android arm64 injector digest")
    assets = destination / "iccoa-relay"
    assets.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory() as temporary:
        temporary = Path(temporary)
        script = temporary / "relay.js"
        shutil.copyfile(SOURCE / "relay.js", script)
        shutil.copyfile(SOURCE / "package.json", temporary / "package.json")
        # frida-compile resolves its Java bridge relative to the entrypoint.
        (temporary / "node_modules").symlink_to(compiler.parent.parent.resolve(), target_is_directory=True)
        subprocess.run([compiler, script, "-o", assets / "relay.js"], cwd=temporary, check=True)
        android_jar = sdk / "platforms/android-34/android.jar"
        for role in ("Client", "Server"):
            classes = temporary / f"{role}-classes"
            dex = temporary / f"{role}-dex"
            classes.mkdir()
            dex.mkdir()
            subprocess.run(
                [javac, "--release", "8", "-cp", android_jar, "-d", classes, SOURCE / f"{role}Transport.java"],
                check=True,
            )
            subprocess.run(
                [sdk / "build-tools/34.0.0/d8", "--min-api", "31", "--lib", android_jar, "--output", dex,
                 *sorted(classes.rglob("*.class"))],
                check=True,
            )
            shutil.copyfile(dex / "classes.dex", assets / f"{role.lower()}.dex")
    with lzma.open(injector, "rb") as source, (assets / "injector").open("wb") as output:
        shutil.copyfileobj(source, output)
    shutil.copyfile(copying, assets / "COPYING")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sdk", "javac", "compiler", "injector", "copying", "destination"):
        parser.add_argument(name, type=lambda value: Path(value).absolute())
    prepare(**vars(parser.parse_args()))
