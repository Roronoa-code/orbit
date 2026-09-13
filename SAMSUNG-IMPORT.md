# Samsung Health migration

Requested 13 September 2026. Source confirmed: Samsung Health. Keep one production Orbit app. All testing stays local; install only after verification.

| Requirement | Implementation / proof | Status |
| --- | --- | --- |
| Real Samsung measurements and workouts | Read-only Health Connect import, Samsung origin filter, complete pagination, private local storage, recorded units/timestamps/IDs | Implemented; local checks pass |
| Remove injected histories | Remove production generators and sample fallback; missing data remains missing; archive and retire prior Orbit test workouts once | Implemented; local checks pass |
| Preserve actual data | Atomic import, repeat-import deduplication, failed-import rollback, preserve profile and Samsung originals | Implemented; local checks pass |
| Existing UI and motion | Connect Home, Body, oxygen, sleep and weekly workout history; local empty/sparse/populated and gesture checks | Local checks pass |
| Actual migration | Requires the user's Health Connect consent and Samsung Health sharing; do not claim import from a build or fixture test | Pending access |

The previous standalone package had no Health Connect permissions or reader. The new production build includes both. Samsung supports sharing steps, exercise, heart rate, sleep, nutrition and body measurements through Health Connect. Shared coverage depends on Samsung Health and the user's permissions. Orbit retains source and coverage and does not synthesize unavailable measurements. Existing real profile settings are separate from the generated histories.

References: [Samsung integration](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect), [sharing and synchronization](https://developer.samsung.com/health/health-connect-faq.html), [Android history access](https://developer.android.com/health-and-fitness/health-connect/read-data), [platform permissions](https://developer.android.com/reference/android/health/connect/HealthPermissions).


Local verification: `node verification/samsung-import.cjs` (atomic model rejection plus empty/populated rendered views at 390 and 320 px); `node verification/check.cjs`; existing home-motion, body-history, interaction-followup and workout-rework checks pass. All phone testing remains prohibited.

`verification/samsung-native.py` builds a fixture package for a local emulator only. `verification/HealthImportCheck.java` writes 508 controlled Health Connect records, including one 61-day-old measurement and exercises the actual platform reader, multiple pages, daily/workout aggregation, repeat import, cancelled import, failed-staging rollback and one-time demo retirement. The platform permission UI must grant fixture access; plain `pm grant` does not add a source to Health Connect's activity-priority list. Orbit's real Connect button was separately checked through both the regular and extended-history consent screens in the local emulator; the production UI reported 508 saved records with extended-history access. Those are test records, not the user's data.

User setup after installation: enable Samsung Health sharing in Samsung Health → Settings → Health Connect. In Orbit → Settings → Samsung Health, tap Connect, allow the required readings and past-data access, then keep Orbit open. Initial consent starts import; Import now refreshes shared records later. Only data Samsung has shared can transfer. An older Samsung export may still be needed if historical records are absent from Health Connect.

Scope: Home activity, heart rate, sleep, food/water, body/oxygen and workout history use shared records. Original metadata, IDs, timestamps, units, detailed sleep intervals, nutrition nutrients, exercise segments and laps remain in private storage. Imported workout metrics are explicitly totals within the session interval; unavailable routes are not invented. No Samsung/Health Connect write or delete permissions are requested. Old test workout JSON is archived privately once; the old browser store is no longer read. Profile preferences and future app-owned workouts survive.

The separate local consumer check (`python verification/samsung-native.py --reader`, then its `HealthImportCheck` activity with `--ez reader true`) reads the fixture through a different package. This reproduced a platform boundary issue that a source reading its own records did not: extended-history aggregation omitted the first hour of steps and midnight-starting distance/energy records. Daily queries now include the beginnings of overlapping intervals and all legal timezone offsets, then discard only the extra date buckets. The same check passes with recent access (507 records) and extended access (508). The exact rebuilt production APK imported 5,010 steps, 5,710 m and 270 kcal for the controlled day. Test packages are local-emulator-only and are excluded from the production APK.

Installed production build `20260913-204125`, SHA256 `aa59ea318108a5e05bb110d2effa1d2b24b13f0cb3829937fc53d893f232358a`, 300625 bytes, using `install -r`; the installed hash matches. Only `com.mani.orbit` remains among the Orbit/old Health packages on the phone. The previous APK is backed up privately in `verification/samsung-import/phone-before.apk`. No phone launch, permission operation, data import or test was performed. Actual Samsung data migration remains pending user consent.
