#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import time

SOURCE = Path(__file__).resolve().parent.parent


def main():
    with tempfile.TemporaryDirectory(prefix="winnative-battlenet-") as directory:
        root = Path(directory)
        shared = root / "shared"
        auth = shared / "auth"
        auth.mkdir(parents=True)
        helper = root / "battlenet-session.exe"
        fixture = root / "Battle.net.exe"
        for source, target in [(SOURCE / "battlenet.c", helper), (SOURCE / "tests/session-fixture.c", fixture)]:
            subprocess.run(["i686-w64-mingw32-gcc", "-Os", "-municode", "-Wall", "-Wextra", "-Werror",
                            str(source), "-o", str(target), "-lcrypt32", "-ladvapi32"], check=True)
        environments = []
        with (root / "wine.log").open("w+") as log:
            try:
                for index in range(2):
                    prefix = root / f"prefix-{index}"
                    env = dict(os.environ, WINEPREFIX=str(prefix), WINEDEBUG="-all")
                    environments.append(env)
                    subprocess.run(["wineboot", "-u"], env=env, stdout=log, stderr=log, timeout=120, check=True)
                    link = prefix / "drive_c/WinNative/Battle.net"
                    link.parent.mkdir(parents=True)
                    link.symlink_to(shared, target_is_directory=True)
                (auth / "account").write_text("winnative-interoperability-test@example.invalid")
                (auth / "pending.token").write_text("US-00000000-0000-0000-0000-000000000000")
                for env, mode in zip(environments, ["--persist", "--restore"]):
                    run_session(env, mode, auth, helper, fixture, log)
                assert (auth / "identity.bin").is_file()
                assert (auth / "database-key.bin").is_file()
                (auth / "session.bin").write_bytes(b"invalid protected data")
                result = subprocess.run(["wine", str(helper), str(fixture), "--persist"], env=environments[1],
                                        stdout=log, stderr=log, timeout=40)
                assert result.returncode == 4, result.returncode
                assert (auth / "status").read_text() == "credential_import_failed"
                print("PASS: malformed protected session refuses launch")
                (auth / "session.bin").unlink()
                (auth / "identity.bin").write_bytes(b"invalid cache state")
                result = subprocess.run(["wine", str(helper), str(fixture), "--persist"], env=environments[1],
                                        stdout=log, stderr=log, timeout=40)
                assert result.returncode == 8, result.returncode
                print("PASS: malformed shared cache refuses launch")
            except BaseException:
                log.flush()
                log.seek(0)
                print(log.read())
                raise
            finally:
                for env in environments:
                    subprocess.run(["wineserver", "-k"], env=env, stdout=log, stderr=log, timeout=15)


def run_session(env, mode, auth, helper, fixture, log):
    for name in ["status", "forwarded"]:
        (auth / name).unlink(missing_ok=True)
    process = subprocess.Popen(["wine", str(helper), str(fixture), mode], env=env, stdout=log, stderr=log)
    seen, versions = set(), set()
    deadline = time.monotonic() + 65
    forwarded = False
    try:
        while time.monotonic() < deadline:
            if (auth / "status").exists():
                seen.add((auth / "status").read_text())
            try:
                versions.add((auth / "session.bin").read_bytes())
            except FileNotFoundError:
                pass
            if "fixture_import_passed" in seen and not forwarded:
                subprocess.run(["wine", str(helper), str(fixture), "--forward", "space separated",
                                'embedded"quote', "trailing\\"], env=env, stdout=log, stderr=log,
                               timeout=10, check=True)
                forwarded = True
            if process.poll() is not None:
                break
            time.sleep(0.1)
        assert process.poll() == 0, "session helper failed or did not stop"
        assert "fixture_import_passed" in seen, seen
        assert "session_saved" in seen, seen
        assert len(versions) >= 2, "rotated session was not saved"
        assert (auth / "forwarded").read_text() == "ok", "concurrent launch arguments changed"
        assert not (auth / "pending.token").exists()
        if mode == "--restore":
            assert "sign_in_required" in seen, seen
            assert not (auth / "session.bin").exists()
        else:
            assert (auth / "session.bin").is_file()
        print(f"PASS: {mode}, concurrent argument forwarding, rotation and shutdown", flush=True)
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    main()
