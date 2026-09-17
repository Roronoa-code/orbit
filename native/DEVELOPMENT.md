# Native verification

Use the existing `verify.ps1` entry point. Its default is now a **read-only doctor**, not an implicit install on a guessed device. Python 3.11+ standard library is required; set `ORBIT_PYTHON` if `python` is not on PATH. `JAVA_HOME`, the existing `local.properties`/`ANDROID_HOME`, and `SAMSUNG_HEALTH_SDK` retain their build meanings.

```powershell
./verify.ps1 -Mode doctor
./verify.ps1 -Mode doctor -Serial emulator-5582
./verify.ps1 -Mode full -Module phone -Serial emulator-5580
./verify.ps1 -Mode full -Module wear -Serial emulator-5582
./verify.ps1 -Mode changed -Module phone -Serial emulator-5580 -Baseline ../verification/native-runs/<passing-phone-run>/manifest.json
./verify.ps1 -Mode replay -Module phone -Serial emulator-5580 -Case workout-control
./verify.ps1 -Mode replay -Module wear -Serial emulator-5582 -Case watch-recovery
./verify.ps1 -Mode replay -Module phone -Serial emulator-5580 -Case diagnostic-boundaries
./verify.ps1 -Mode replay -Module phone -Serial emulator-5580 -Case explore-source-reversal
```

The equivalent direct interface is `python -B dev.py doctor|full|changed|replay --module phone|wear --serial emulator-N`, with `--baseline` or `--case` as appropriate. There is no physical-device override. Commands check emulator hardware before installing or running anything. Each module is explicit; run both full commands for cross-module acceptance. Doctor prints JSON and does not build, install, create evidence files or change settings. Supplying a serial adds read-only emulator configuration checks.

Full verification runs the existing module's entire Android instrumentation suite, plus the small standard-library checks protecting this command's selection/failure/device guards. It builds both APKs, verifies signatures, rejects HTML/JS/CSS assets in the native app, installs only on the explicit emulator and compares both installed APK hashes. A zero shell exit code alone cannot pass instrumentation: the runner must report a completed positive test count and no failure/ignored/assumption statuses.

Changed verification compares source bytes, additions and deletions against an explicitly supplied **passing full** manifest for that module. Shared model/build/tooling and Android resources affect both apps; legacy Android backend source affects phone; module-local changes affect that module. It runs the full affected module suite rather than guessing individual test methods. A changed environment also triggers a run. If neither changed, its result is `unchanged` with skipped checks and the prior evidence reference, never a fresh runtime pass. A replay or skipped run cannot be used as a full baseline. Run both modules when shared files change. There is no automatic baseline regeneration or update switch.

Replay invokes the existing workout-control and Watch recovery fixtures, plus the seeded diagnostic-boundary and Explore source-update/reversal cases documented in [DIAGNOSTICS.md](DIAGNOSTICS.md). The new cases specify their clocks, seed and input ordering. These are isolated synthetic fixtures, not recorded-device gesture, sensor or paired-transport acceptance. Every APK now embeds the native source fingerprint; verification checks it against the current source snapshot before installation.

Every verification attempt writes a unique directory under `../verification/native-runs/`, including failures. `manifest.json` records source files and aggregate fingerprint, Git HEAD/dirty status, observed toolchain/SDK environment, emulator configuration, APK/signing identities, installed hashes, check statuses/counts/times and log hashes. It distinguishes checks not reached and preflight blockers. Source changes during a build or test invalidate that run. The file is written atomically. Existing logs/manifests are never overwritten. Build output, real health databases, media and device dumps are not included in source fingerprints.

These are developer records, not an automatic upload or a production diagnostics bundle. They do not prove paired Data Layer delivery, physical sensor accuracy, motion quality, battery behavior, upgrade safety or user design acceptance. Those remain separate goal gates. [Dependency provenance](DEPENDENCIES.md) distinguishes locked versions from verified publisher trust.
