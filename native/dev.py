"""Run Orbit's existing emulator checks with explicit source/artifact provenance.

Doctor is read-only. Verification never accepts a physical device, and an emulator
pass is not paired transport or hardware acceptance. Python standard library only.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
RUNS = REPO / "verification" / "native-runs"
MODULES = ("phone", "wear")
WRAPPER_SHA = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
REPLAYS = {
    "workout-control": ("phone", "com.mani.orbit.WorkoutControlTest"),
    "watch-recovery": ("wear", "com.mani.orbit.wear.WatchRecoveryTest"),
    "explore-source-reversal": ("phone", "com.mani.orbit.ExploreIslandTest#seededReversalWithSourceUpdateAndTapAfterSettle"),
    "diagnostic-boundaries": ("phone", "com.mani.orbit.DiagnosticTraceTest"),
}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fingerprint(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def source_snapshot():
    files = set()
    directories = [ROOT / m / "src" for m in (*MODULES, "sync")]
    directories += [ROOT / "gradle", REPO / "android" / "src", REPO / "android" / "res"]
    for directory in directories:
        for parent, dirs, names in os.walk(directory):
            dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".git", "__pycache__")]
            files.update(Path(parent) / name for name in names)
    for directory in (ROOT, *(ROOT / m for m in (*MODULES, "sync"))):
        files.update(p for p in directory.iterdir() if p.is_file() and
                     (p.suffix in (".kts", ".properties", ".lockfile", ".py", ".ps1", ".bat") or p.name == "gradlew"))
    # Machine configuration is hashed, never copied into the manifest.
    values = {p.relative_to(REPO).as_posix(): digest(p) for p in sorted(files)}
    return {"fingerprint": fingerprint(values), "files": values}


def affects(path, module):
    if path.startswith("android/res/"):
        return True  # Both native apps consume shared resources.
    if path.startswith("android/src/"):
        return module == "phone"
    for candidate in MODULES:
        if path.startswith(f"native/{candidate}/"):
            return candidate == module
    return True  # Shared model, Gradle inputs and developer tools are common.


def changed_paths(before, after):
    return sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))


def validate_baseline(baseline, module):
    if baseline.get("schema") != 1 or baseline.get("module") != module or baseline.get("status") != "passed" or baseline.get("mode") != "full":
        raise ValueError("Baseline must be a passing full verification of this module")
    source = baseline.get("source", {})
    if not isinstance(source.get("files"), dict) or fingerprint(source["files"]) != source.get("fingerprint"):
        raise ValueError("Baseline source fingerprint is inconsistent")
    tests = [c for c in baseline.get("checks", []) if c.get("name") == "instrumentation"]
    if len(tests) != 1 or tests[0].get("status") != "passed" or tests[0].get("testsPassed", 0) <= 0:
        raise ValueError("Baseline has no completed instrumentation evidence")


def write_manifest(directory, manifest):
    manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
    temporary = directory / "manifest.tmp"
    temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(directory / "manifest.json")
    print(f"{manifest['status']}: {directory / 'manifest.json'}", flush=True)


def capture(command, timeout=30, env=None):
    result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env, timeout=timeout,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{Path(str(command[0])).name} failed with exit {result.returncode}")
    return result.stdout.strip()


def toolchain():
    jdk = Path(os.environ.get("JAVA_HOME", "C:/HA/HEALTH APP/.local/toolchains/jdk-17.0.20.1+1"))
    local = ROOT / "local.properties"
    sdk_line = re.search(r"^sdk\.dir=(.+)$", local.read_text(), re.M) if local.exists() else None
    sdk = Path(sdk_line[1].replace("\\:", ":").replace("\\\\", "\\").strip() if sdk_line else
               os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
    java = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
    adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    sdk_zip = Path(os.environ.get("SAMSUNG_HEALTH_SDK", str(Path.home() / "Downloads/samsung-health-data-sdk-1.1.0.zip")))
    sensor_zip = Path(os.environ.get("SAMSUNG_SENSOR_SDK", str(Path.home() / "Downloads/samsung-health-sensor-sdk-v1.4.1.zip")))
    if not sensor_zip.is_file():
        raise RuntimeError("Missing Samsung Sensor SDK archive (SAMSUNG_SENSOR_SDK)")
    verification = ROOT / "gradle/verification-metadata.xml"
    if not verification.is_file():
        raise RuntimeError("Missing reviewed Gradle dependency verification metadata")
    versions = sorted((sdk / "build-tools").glob("*/lib/apksigner.jar"), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.parent.name)))
    platforms = [p.parent for p in (sdk / "platforms").glob("*/source.properties")
                 if re.search(r"^AndroidVersion.ApiLevel=37(?:\.0)?\s*$", p.read_text(), re.M)]
    if not java.is_file() or not adb.is_file() or not sdk_zip.is_file() or not versions:
        raise RuntimeError("Missing Java, Android build tools/platform tools or Samsung SDK archive")
    if len(platforms) != 1:
        raise RuntimeError("Expected one installed Android API 37 platform")
    if digest(ROOT / "gradle/wrapper/gradle-wrapper.jar") != WRAPPER_SHA:
        raise RuntimeError("Gradle wrapper differs from the independently checked official checksum")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    observed = {
        "javaVersion": capture([java, "-version"]), "javaSha256": digest(java),
        "adbVersion": capture([adb, "version"]), "adbSha256": digest(adb),
        "android37JarSha256": digest(platforms[0] / "android.jar"),
        "android37PropertiesSha256": digest(platforms[0] / "source.properties"),
        "apkSignerBuildToolsVersion": versions[-1].parent.parent.name,
        "sdkBuildTools": {p.parent.name: digest(p) for p in (sdk / "build-tools").glob("*/source.properties")},
        "apksignerSha256": digest(versions[-1]),
        "samsungSdkArchiveSha256": digest(sdk_zip),
        "samsungSensorArchiveSha256": digest(sensor_zip),
        "samsungSdkTrust": "Supplied archives, not Samsung-published checksums; see DEPENDENCIES.md",
        "dependencyVerification": {"metadataSha256": digest(verification),
            "policy": "Strict Gradle checksum enforcement; independent distribution and Kotlin publisher review in DEPENDENCIES.md"},
    }
    return java, adb, versions[-1], env, observed


def device_info(adb, serial):
    if not re.fullmatch(r"emulator-\d+", serial or ""):
        raise ValueError("An explicit local emulator serial is required; physical devices are not accepted")
    hardware = capture([adb, "-s", serial, "shell", "getprop", "ro.hardware"])
    if hardware not in ("ranchu", "goldfish"):
        raise ValueError("Device is not a verified local emulator")
    result = {"serial": serial, "hardware": hardware}
    for name in ("ro.build.version.sdk", "ro.build.fingerprint", "ro.product.cpu.abi", "debug.hwui.renderer"):
        result[name] = capture([adb, "-s", serial, "shell", "getprop", name])
    for name in ("size", "density"):
        result[name] = capture([adb, "-s", serial, "shell", "wm", name])
    result["fontScale"] = capture([adb, "-s", serial, "shell", "settings", "get", "system", "font_scale"])
    graphics = capture([adb, "-s", serial, "shell", "dumpsys", "SurfaceFlinger"])
    result["graphics"] = next((line.strip() for line in graphics.splitlines() if line.strip().startswith("GLES:")), "Not reported")
    return result


def instrumentation_count(output):
    matches = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.M)
    if len(matches) != 1 or int(matches[0]) <= 0 or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M):
        raise RuntimeError("Instrumentation did not report one completed passing run")
    if re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1234]\b", output):
        raise RuntimeError("Instrumentation reported a failure or assumption/ignored check; inspect the log")
    return int(matches[0])


def execute(command, directory, name, manifest, env, timeout):
    check = {"name": name, "status": "running", "log": name + ".log"}
    manifest["checks"].append(check)
    started = time.monotonic()
    print(name + "…", flush=True)
    try:
        with (directory / check["log"]).open("w", encoding="utf-8") as output:
            result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env, timeout=timeout,
                                    stdout=output, stderr=subprocess.STDOUT)
        check["exitCode"] = result.returncode
        if result.returncode:
            raise RuntimeError(name + " failed; see its log")
        check["status"] = "passed"
        return (directory / check["log"]).read_text(encoding="utf-8", errors="replace")
    except Exception:
        check["status"] = "failed"
        raise
    finally:
        check["seconds"] = round(time.monotonic() - started, 3)
        log = directory / check["log"]
        if log.exists():
            check["logSha256"] = digest(log)


def run(args):
    java, adb, signer, env, environment = toolchain()
    source = source_snapshot()
    device = device_info(adb, args.serial) if args.serial else None
    if args.mode == "doctor":
        print(json.dumps({"schema": 1, "mode": "doctor", "source": source["fingerprint"],
                          "environment": environment, "device": device, "readOnly": True}, indent=2))
        return 0
    if not args.module or not device:
        raise ValueError("Verification requires --module phone|wear and --serial emulator-N")
    if args.mode == "replay" and (not args.case or REPLAYS[args.case][0] != args.module):
        raise ValueError("Choose a replay case belonging to the explicit module")
    if args.mode == "changed" and not args.baseline:
        raise ValueError("Changed verification requires an explicit previously passing --baseline manifest")

    directory = RUNS / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + "-" + args.module)
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {"schema": 1, "mode": args.mode, "module": args.module, "status": "running", "source": source,
                "startedAt": datetime.now(timezone.utc).isoformat(), "environment": environment,
                "device": device, "checks": [], "artifacts": {}, "remainingGates": [
                    "Paired authenticated transport", "Physical sensor/performance/visual acceptance",
                    "Personal signing/in-place upgrade", "Samsung SDK provenance and authorized Watch developer-mode access"]}
    try:
        manifest["git"] = {"head": capture(["git", "rev-parse", "HEAD"]),
                           "status": capture(["git", "status", "--porcelain=v1", "--untracked-files=normal", "--", ".", "../android/src", "../android/res"])}
        if args.mode == "changed":
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
            validate_baseline(baseline, args.module)
            changes = changed_paths(baseline["source"]["files"], source["files"])
            relevant = [p for p in changes if affects(p, args.module)]
            manifest["selection"] = {"basis": str(args.baseline.resolve()), "basisSha256": digest(args.baseline),
                "changedFiles": changes, "relevantFiles": relevant, "policy": "All existing tests in the affected module; no method-level guesswork"}
            same_environment = baseline["environment"] == environment and baseline["device"] == device
            if not relevant and same_environment:
                manifest["checks"].append({"name": "instrumentation", "status": "skipped", "reason": "No affected source or environment change from the explicit passing baseline"})
                manifest["status"] = "unchanged"
                return 0

        gradle = [java, "-classpath", ROOT / "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain", "--console=plain"]
        execute([sys.executable, "-B", "-m", "unittest", "test_dev"], directory, "developer-checks", manifest, env, 30)
        execute([*gradle, f":{args.module}:assembleDebug", f":{args.module}:assembleDebugAndroidTest"], directory, "build", manifest, env, 600)
        module = ROOT / args.module
        apk = module / f"build/outputs/apk/debug/{args.module}-debug.apk"
        test_apk = module / f"build/outputs/apk/androidTest/debug/{args.module}-debug-androidTest.apk"
        for name, path in (("app", apk), ("tests", test_apk)):
            signing = capture([java, "-jar", signer, "verify", "--print-certs", path])
            cert = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})", signing)
            if not cert:
                raise RuntimeError("Missing verified APK signing certificate")
            manifest["artifacts"][name] = {"path": path.relative_to(REPO).as_posix(), "sha256": digest(path), "certificateSha256": cert[1]}
        if manifest["artifacts"]["app"]["certificateSha256"] != manifest["artifacts"]["tests"]["certificateSha256"]:
            raise RuntimeError("Application and instrumentation signing certificates differ")
        with zipfile.ZipFile(apk) as package:
            if any(n.lower().endswith((".html", ".js", ".css")) for n in package.namelist()):
                raise RuntimeError("Web assets unexpectedly packaged in native application")
            identity = json.loads(package.read("assets/orbit-native-build.json"))
            if identity != {"schema": 1, "product": "orbit-native", "source": source["fingerprint"]}:
                raise RuntimeError("Embedded native build identity does not match this source snapshot")
            manifest["artifacts"]["app"]["embeddedIdentity"] = identity
        if source_snapshot() != source:
            raise RuntimeError("Source changed during the build; retry on a stable source snapshot")
        for name, path in (("app", apk), ("tests", test_apk)):
            execute([adb, "-s", args.serial, "install", "-r", path], directory, "install-" + name, manifest, env, 120)
        for name, package in (("app", "com.mani.orbit"), ("tests", "com.mani.orbit.test")):
            remote = capture([adb, "-s", args.serial, "shell", "pm", "path", package]).removeprefix("package:")
            if not remote.startswith("/data/app/") or "\n" in remote:
                raise RuntimeError("Unexpected installed APK layout")
            installed_hash = capture([adb, "-s", args.serial, "shell", "sha256sum", remote]).split()[0]
            if installed_hash != manifest["artifacts"][name]["sha256"]:
                raise RuntimeError("Installed emulator APK does not match the built artifact")
            manifest["artifacts"][name]["installedSha256"] = installed_hash
        command = [adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r"]
        if args.mode == "replay":
            command += ["-e", "class", REPLAYS[args.case][1]]
            manifest["replay"] = {"case": args.case, "class": REPLAYS[args.case][1], "basis": "Existing isolated fixture; controlled scenario clocks, fresh protocol nonces; not a captured-device trace"}
            if args.case in ("explore-source-reversal", "diagnostic-boundaries"):
                manifest["replay"].update(seed=915, clock="Compose test clock and synthetic process-relative nanoseconds",
                    basis="Explicit ordered inputs and synthetic source; no personal database; not physical motion or transport evidence")
        command += ["com.mani.orbit.test/androidx.test.runner.AndroidJUnitRunner"]
        output = execute(command, directory, "instrumentation", manifest, env, 900)
        try:
            manifest["checks"][-1]["testsPassed"] = instrumentation_count(output)
        except Exception:
            manifest["checks"][-1]["status"] = "failed"
            raise
        if source_snapshot() != source:
            raise RuntimeError("Source changed while tests ran; this run cannot qualify the current tree")
        manifest["status"] = "passed"
        return 0
    except Exception as error:
        manifest["status"] = "failed"
        manifest["failure"] = str(error)
        print(str(error), file=sys.stderr)
        return 1
    finally:
        reached = {check["name"] for check in manifest["checks"]}
        manifest["checksNotRun"] = [name for name in ("developer-checks", "build", "install-app", "install-tests", "instrumentation") if name not in reached]
        write_manifest(directory, manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("doctor", "full", "changed", "replay"), nargs="?", default="doctor")
    parser.add_argument("--module", choices=MODULES)
    parser.add_argument("--serial")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--case", choices=REPLAYS)
    args = parser.parse_args()
    try:
        return run(args)
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        failure = {"schema": 1, "mode": args.mode, "module": args.module, "status": "blocked",
                   "checks": [{"name": "preflight", "status": "blocked", "reason": str(error)}]}
        if args.mode == "doctor":
            print(json.dumps(failure, indent=2))
        else:
            directory = RUNS / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + "-preflight")
            directory.mkdir(parents=True, exist_ok=False)
            write_manifest(directory, failure)
        return 2


if __name__ == "__main__":
    sys.exit(main())
