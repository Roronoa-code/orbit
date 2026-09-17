# Native dependency provenance

The working toolchain is retained: Gradle 9.6.0, Android Gradle Plugin 9.4.0, Kotlin Compose plugin 2.3.21, Java 17, Android compile API 37 and Compose BOM 2026.08.00. No new runtime library was introduced by the developer-command work.

R1 explicitly requires JankStats. The shared native module now uses `androidx.metrics:metrics-performance:1.0.0`, the version in Android's [official JankStats usage documentation](https://developer.android.com/topic/performance/jankstats). All three module lockfiles were refreshed for this addition across debug/test and release configurations. This is a named diagnostics dependency change, not a toolchain upgrade; publisher trust review remains open below.

Release lint exposed Play services selecting Fragment 1.1.0 (and 1.2.4 on one debug path), below the Activity Result API's required 1.3.0 floor. Both application modules now constrain that existing transitive library to the already available 1.5.4 version. This repairs dependency compatibility without introducing Fragment navigation or suppressing lint. `fragment-origin.log` records the dependency path; debug/test and release builds pass with the updated locks.

`phone`, `wear` and `sync` now use Gradle's strict dependency locks. Their generated `gradle.lockfile` files capture the resolved configurations, including debug/test and release. Lock generation includes actual compilation because AGP creates some SDK configurations only while executing tasks. Locked versions are constraints; they do not authenticate downloaded bytes or publisher identity. See the official [dependency locking guide](https://docs.gradle.org/current/userguide/dependency_locking.html).

The Gradle distribution checksum was retrieved from [Gradle's official checksum endpoint](https://downloads.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256) on 15 September 2026 and added to the wrapper properties:

`bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`

The existing wrapper JAR matches the separately retrieved [official wrapper checksum](https://downloads.gradle.org/distributions/gradle-9.6.0-wrapper.jar.sha256):

`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

Doctor and verification reject a different wrapper JAR. The wrapper checks its distribution checksum when downloading it. Existing extracted Gradle installations and Java/SDK binaries still require their normal publisher/distribution trust review; observing their hashes is not that review.

The phone uses the existing Samsung Health Data SDK 1.1.0 archive, selecting only `1.1.0/libs/samsung-health-data-api-1.1.0.aar`. Observed original archive SHA-256:

`7a51440d840e099769b150c6414365bc43eecb61c01fa5bf6d0e54e09c2b663f`

The SDK's [official overview and distribution guidance](https://developer.samsung.com/health/data/overview.html) describe the product and access requirements. The local hash records the supplied archive; it is not represented as a Samsung-published checksum. Verification fingerprints the selected archive and actual Android platform JAR/properties, Java executable, ADB and APK signer. Any changed archive/environment triggers changed verification. The build still honors `SAMSUNG_HEALTH_SDK` explicitly.

## Dependency verification and trust boundary

The pinned Health Services `1.1.0-rc02` exposes `ExerciseInfo.exerciseTrackedStatus` publicly but marks its `ExerciseTrackedStatus` IntDef as library-restricted. Android's own [ExerciseClientKtx sample at a fixed revision](https://github.com/android/health-samples/blob/a960a72ae4a162d9df15c13c33d77a636db085f3/health-services/ExerciseSampleCompose/app/src/main/java/com/example/exercisesamplecompose/data/ExerciseClientKtx.kt) suppresses that annotation to inspect ownership. Orbit confines the same compatibility exception to the three workout constants and the Samsung guard's private idle constant, rather than suppressing service or project lint. The [public ExerciseClient contract](https://developer.android.com/reference/androidx/health/services/client/ExerciseClient) requires ownership checks before starting or recovering an exercise. Values, ownership checks and dependency versions remain; unknown values still fail closed. Reassess this narrow exception when changing the pinned SDK.

On 15 September 2026, all **744 resolved artifacts in 424 components**, including plugins, metadata and transitives used by debug/test/release/lint, were compared with independent distribution responses before enabling `gradle/verification-metadata.xml`. Of these, 411 matched repository SHA-256 files and 333 matched freshly streamed artifact bytes. Sources were Google's official Maven host (439) and Maven Central (305), the repositories named by [Android's documentation](https://developer.android.com/build/remote-repositories). Gradle module aliases and the Guava platform variant were resolved through published module metadata; the downloaded bytes still had to match the exact candidate hash. Failed initial URL guesses were not bypassed or trusted.

The **35 Kotlin JAR signatures** were additionally verified with the full release-key fingerprint `2FBA29D08D2E25EE84C132C30729A0AFF8999A87`, independently identified in [Kotlin's security documentation](https://kotlinlang.org/docs/security.html). Other artifact entries establish repository HTTPS origin and byte integrity; they do **not** assert publisher PGP identity. Runtime Gradle verification uses exact SHA-256 pins, including POM/module metadata, without group-wide exceptions or trusted-artifact bypasses. The [Gradle guide](https://docs.gradle.org/current/userguide/dependency_verification.html) explains the separate checksum/signature trust boundaries.

Evidence is in `verification/dependency-trust/`: per-artifact URLs/methods in `review.json`, the official key reference and signature files, original candidate, strict build log, and a negative check. Deliberately replacing AGP's expected checksum caused dependency verification to reject the build; restoring the exact reviewed file restored a passing build. Debug, test, release and release lint subsequently pass under strict verification. Initial lint failure in the Samsung ownership guard was repaired with the existing narrow SDK workaround above.

`doctor` now requires and fingerprints both supplied Samsung archives and the reviewed metadata. Five developer checks cover preflight and verification integrity. The supplied Sensor SDK archive SHA-256 is `c2e950774650ff212ae495525fc22714f5c8723a50f91ceb1514a9ec92c42be9`; the extracted AAR remains separately pinned by the Wear build. These local Samsung archive hashes are not Samsung-published checksums, and SDK distribution/package/signature authorization remains separate.

For future changes, compare every new artifact against its authoritative distribution and review required publisher signatures before accepting its pin. Never automatically trust generated hashes, ignore failed signatures or disable strict verification to pass a build. Lock updates must name the intended dependency change and rerun affected checks. Normal verification does not run `--write-locks` or `--write-verification-metadata`.

Personal in-place signing remains a separate gate: the native development certificate differs from the installed original Orbit certificate. Do not uninstall the original or deploy a second clone to bypass that difference.

Personal delivery clarification: this build is sideloaded by its owner, not a public/store release. Samsung partner approval is not a completion gate. The official Health Sensor Service developer-mode route is documented for testing/debugging; actual authorized Watch access and license acceptance remain to verify. This does not replace Android package signing or permissions.
