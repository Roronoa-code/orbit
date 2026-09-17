# Sensor ordering and clock changes

15 September 2026. This is shared native journal behavior for phone and Watch. Original Samsung imports remain separate.

Android exposes a [boot count](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT) as an integer. Orbit already derives a boot identity from that count and its own installation identity. The Watch now includes the count as optional metadata alongside the existing boot UUID and monotonic sensor timestamp. This is Orbit's ordering policy, not an Android guarantee about delivery order.

## Selection policy

- Within a boot, sensor elapsed time orders readings. An ID breaks a timestamp tie deterministically. Wall-clock changes and late delivery cannot move an older sensor sample ahead of a newer one.
- Within one installation, known native boot counts order different boots. Counts are never compared between different watches.
- The Watch's Today/pulse data loader, Tile and complication providers explicitly request their current boot and installation. A boot without a new reading returns no current reading; previous readings remain in the journal and the phone's recorded view.
- Older senders omit the optional count. Their boot UUIDs cannot establish reboot order. Within each such boot sensor order still works. Across unknown boots, the recorded wall time remains the fallback and the returned view carries an explicit ordering-uncertain marker. The phone displays that qualification. Learning the count for a boot qualifies its existing original records without rewriting them.
- Native boot identities/counts must remain consistent within an installation. Contradictory identities roll back the whole incoming batch. Delivery time is never substituted for capture time.

## Compatibility and storage

`bootCount` is additive optional metadata in the existing version-1 reading payload. The pinned old reader ignores unknown fields; the new reader still accepts an omitted count. Legacy encoding without the field remains byte-for-byte unchanged. Explicit null, strings, fractional numbers, negative values and values beyond Android's integer range are rejected.

The journal adds indexed boot/elapsed columns and a boot-order table in a transaction. The migration reads existing payloads once and leaves their JSON, hash, timestamps, receipts and queued wire bytes unchanged. A corrupt record rolls back the migration and closes the failed connection. It does not erase the record or pretend that migration succeeded.

Latest selection uses one indexed sensor lookup per boot, instead of parsing the entire archive during each refresh. It retains at most one candidate per boot. The same source-scoped rule serves the Watch and phone. This changes selection, not source totals or Samsung's interpretation of merged activity.

Repeated callback capture retains already known boot metadata, preserves valid quality when an otherwise identical callback supplies unknown quality, and accumulates time uncertainty. Adding metadata or uncertainty produces one meaningful revision; subsequent duplicates do not produce another outbox entry. A later callback cannot silently make an uncertain original certain again.

## Evidence and limits

The original native regression failed when an older sample with a larger wall-clock timestamp arrived after a newer monotonic sample. Native checks now exercise backward clock movement, late backlog, reboot ordering, legacy fallback, source separation, reopen, conflicting boot rollback, failed migration rollback, preserved original JSON/hash/queue/receipt bytes, optional-field validation and duplicate/quality preservation. The actual Watch Health Services check verifies that the boot count saved with callbacks matches the public system value.

No physical clock was changed and neither physical device was controlled. These native emulator/database cases do not establish physical paired-radio ordering or device acceptance. A legacy sender with no comparable boot metadata cannot provide certain cross-boot order; the app exposes that limitation instead of inventing a timestamp or deleting older records.


## Complete Samsung measurements

Scalar measurement history now uses the same boot-order policy as ordinary readings. Indexed boot/elapsed metadata is derived from the original measurement payload, with identity and receipt checks in the schema transaction. Original vendor values, wire bytes, receipts and pending transfers are unchanged. A partial index catches rows inserted by an older binary after a downgrade without scanning the archive on every open.

History keeps a fixed selected ID and reciprocal previous/next neighbors across boots. Inside a boot it uses a covering `(sensor_elapsed,id)` range seek. Known boot heads and legacy wall-time heads are merged as two ordered streams; a mixed pairwise comparator would be non-transitive after clock rollback. Only the visible measurement payload is decoded. Phone and Watch qualify unknown cross-boot order; an already open Watch result retains its qualification during refresh.

Focused emulator evidence is in [the measurement-order checkpoint](verification/samsung-import/native-measurement-order/checkpoint.json): 18 distinct phone checks and 6 Watch checks, with affected checks rerun after repairs and the indexed-query change. The final three history tests exercise rollback, reboots, late backlog, ties, reciprocal browsing, source isolation, learned boot metadata, collision rollback, atomic old-schema migration, exact originals/queue/receipts, old-binary rows and the actual SQLite covering range seek. The separate ECG follow-through is documented below; scalar checks alone do not qualify ECG or physical clocks.


## ECG recordings

New ECG packets carry optional `startedElapsedMs` and `bootCount` from the same existing Watch clock anchor. Start wall time is derived from that captured elapsed value once. Every chunk/completion and interrupted-process recovery keeps those original fields. Strict integer/range/identity checks reject conflicting metadata; a start clock cannot follow its own callback receipt. These are capture/receipt clocks, not calibrated waveform sample times. Omitted fields keep the legacy v1 encoding unchanged.

Modern ECG history uses indexed sensor-time seeks within each boot and the same merged boot policy across boots. Legacy records keep null elapsed time: migration registers their existing boot identities without opening or changing encrypted packets or queued ciphertext. A later packet can supply actual missing metadata, and shared readings can qualify a boot count. History never substitutes arrival time, estimates a start from waveform points or fills missing data.

The phone loads an atomic page with stable selection and reciprocal neighbors. Legacy/modern entries within one boot merge recorded wall fallback with the native elapsed stream using the same ordering helper. Only that legacy boot's scalar metadata is sorted; all-modern browsing uses indexed seeks and waveform decryption remains deferred until expansion. Unknown cross-boot order or multiple records without comparable elapsed clocks shows an explicit qualification and neutral Previous/Next labels. One legacy record in a known boot alone does not cause a false warning.

[ECG order checkpoint](verification/samsung-import/native-ecg-order/checkpoint.json): 16 distinct focused phone checks and 4 Watch checks, plus final affected rechecks. Original codec/encryption/sensor foundation is retained and its source hashes verified. This is local emulator evidence, not physical electrode, paired radio or signing qualification.
