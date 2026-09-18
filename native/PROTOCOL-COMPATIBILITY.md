# Phone–Watch compatibility

R2 uses the existing signed Google Data Layer. Static `CapabilityClient` advertisements are the compatibility exchange; there is no second discovery server, identity, pairing step or custom hello timer. Both apps keep their package/signature and existing phone/Watch role checks. [Official CapabilityClient reference](https://developers.google.com/android/reference/com/google/android/gms/wearable/CapabilityClient).

## Supported contract

The oldest supported native counterpart is the pre-negotiation checkpoint `eee1b8c8cc84646281fd55720fa54fb1333ab87d24a1adf5905d45407bcf295b` (the native sync/privacy checkpoint). It already implements all four v1 families below. This is a declared native compatibility floor, not a promise that every earlier development prototype or the older HTML APK implements a Watch protocol.

| Family capability | Direction / operation | Existing path and version |
| --- | --- | --- |
| `orbit_readings_v1` | Watch originals → phone journal | `/orbit/v1/readings`, payload 1 |
| `orbit_workouts_v1` | Watch history/live session revisions → phone journal | `/orbit/v1/workouts`, payload 1 |
| `orbit_workout_control_v1` | Phone command → Watch, then authoritative confirmation | `/orbit/v1/workout-control`, payload 1 |
| `orbit_health_context_v1` | Phone Samsung context → Watch saved view | `/orbit/v1/health-context`, payload 1 |
| `orbit_live_v1` | Phone lease → Watch; Watch answer and live heart and step pushes → phone display | `/orbit/v1/live`, payload 1 |

`orbit_live_v1` is display-only and best effort. While Orbit is on the phone's screen, the phone sends a lease (`kind: request`, `until`) to each reachable watch that advertises the family, renewing it every 20 s; the watch caps any lease at 45 s and a lease of 0 releases it. Unless the wearer stopped it, a request starts the watch's live heart recording by itself, as a health foreground service that records nothing and stops on the first callback after the lease ends. The watch answers each request at once with what it can offer (`streaming`, `passive`, `needs_access` or `off`), its newest valid heart reading and its own step count since midnight (`steps`, `stepsAt`), flushes Health Services' passive sensors, and while the lease holds pushes each newly captured heart reading and step count (a live recording's once-a-second heart thinned to one per 1.5 s; a new step count always goes). The phone adds the steps the watch has counted since Samsung Health last caught up to Samsung's total, hour by hour, so walking without the phone moves the day's count within seconds and Samsung catching up counts nothing twice. A watch that sends no step fields decodes as having none. Nothing in it is journaled or acknowledged: every reading still travels the acknowledged `orbit_readings_v1`/heart-batch path, so a lost live message costs freshness, never data. An older counterpart without the capability is simply never sent it, and an unknown state or newer version is ignored rather than rejected.

New binaries advertise `orbit_protocol_v1` and all supported family names alongside their existing role. A future binary can advertise more than one family version to retain older counterparts. New mandatory semantics must use a new family version; they must not change the meaning of v1 in place. Extra optional fields do not grant support for a new command.

Before draining a reading/history queue, requesting a workout control, or requesting fresh phone context, the sender obtains one successful reachable-capabilities snapshot. It finds the intended node by the existing role/owner rules, then considers only that node's advertisements. Support on another connected node cannot enable an operation. There is no durable positive compatibility cache to survive an update incorrectly.

| Observed counterpart | Outcome |
| --- | --- |
| Supported legacy role, no protocol/family advertisements | Defined four-family v1 profile; no new handshake reply required |
| Current marker + recognized v1 family | Use existing v1 path; same validation, transaction and receipt |
| Partial advertisement or marker with a missing family | Disable only that family; explain the counterpart needing an update |
| Counterpart offers only a newer version of that family | Explain that this device's app needs updating; other recognized v1 families continue |
| Newer protocol marker but recognized v1 family names | Recognized families still work; unknown names confer no support |
| Capability query fails / no authenticated role | No legacy fallback; retain data/cache and retry the connection |
| Multiple phone owners | Preserve the existing single-phone requirement; do not guess an owner |

Capabilities can change after a snapshot. Receivers still validate actual packets; reachability or an earlier advertisement never replaces parsing. Changes/removals/new version names wake the existing Watch work through the capability listener. Phone commands and Watch refreshes query again on the next attempt.

## Originals, rejection and retry

The existing SQLite outbox now supports an indexed family filter. Reading and workout work have separate unique WorkManager jobs, each with exponential backoff starting at 30 seconds. A bounded serialized drain retains the current single-sender ceiling. An unsupported history head therefore does not monopolize reading retries, and vice versa. This is independent scheduling over one journal, not a second data store. Existing queued/periodic `WatchSyncWorker` work without family input dispatches both jobs, so old scheduled work remains useful.

Unknown versions and workout commands fail before interpretation or journal commit. An authenticated receiver returns a bounded negative response at `/orbit/v1/protocol-rejected`, containing only known family, reason and the attempted packet's SHA-256 digest. It contains no health values or route. This response is **never an ACK** and cannot delete an outbox item. Rejection messages do not themselves generate rejection replies. Old counterparts that do not listen to the new path keep their original safe no-ACK/retry behavior.

The Watch matches a negative reply to the exact pending bytes. The phone also requires the active command's peer, stage, token-bound bytes and unexpired lifetime. A delayed reply cannot reject another command or override an already accepted command. Confirmation of a workout still requires the newer durable Watch revision; a send or negative response never counts as success.

Queued packets are validated against this binary before sending, so a downgrade cannot blindly transmit a future format as v1. Unknown future paths remain on disk and produce local-update guidance. A negative response, missing receipt, disconnection or process death leaves the sender's originals intact. Only the existing committed, ID/hash-matched receipt removes a packet. A successful compatible drain cannot label the entire queue synced while other originals remain.

Phone context retains its existing durable v1 DataItem publication while offline. Publication is a version-namespaced cache, not an ephemeral command requiring both apps to be awake. Watch fresh-publication requests negotiate support; saved context remains readable independently. An unsupported cached version shows Watch-update guidance and does not replace a previously loaded view with invented data. Samsung originals on the phone are unaffected.

## Verification boundary

`PeerProtocolTest` uses real Android JSON parsing and isolated SQLite journals. It checks legacy/current/partial/newer advertisements for both roles, missing identity, per-family update guidance, all four unsupported payload versions, unknown commands, exact rejection correlation/expiry, duplicate delivery after a lost receipt, preserved future bytes/paths, and compatible readings past a blocked history head. Existing journal, workout-control, native UI, ambient, recovery and lifecycle suites remain required.

These local checks do not substitute for two real app versions exchanging advertisements and messages on a paired phone/Watch. Paired mixed-version delivery, upgrade-time capability propagation, reconnect under a sustained backlog and physical battery cost remain part of the original qualification gate. No physical device is controlled while the user is asleep.
