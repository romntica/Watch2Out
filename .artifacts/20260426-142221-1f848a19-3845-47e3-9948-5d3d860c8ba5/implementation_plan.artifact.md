# Fail-Safe Incident Transfer Retries

This plan outlines a robust mechanism for ensuring critical EDR reports and audio evidence are delivered to the Mobile app, even if the initial connection is lost.

## Proposed Changes

### [Module: :shared]
Add necessary models for pending asset metadata.

#### [PendingIncident.kt](file:///M:/Study/Watch/Watch2Out/shared/src/main/kotlin/com/jinn/watch2out/shared/model/PendingIncident.kt) [NEW]
- Define `PendingIncident` class to track an EDR JSON and its associated audio file path.

---

### [Module: :wear]
Implement disk persistence for pending incidents and background retry logic.

#### [IncidentAssetStore.kt](file:///M:/Study/Watch/Watch2Out/wear/src/main/kotlin/com/jinn/watch2out/wear/data/IncidentAssetStore.kt) [NEW]
- Create a storage class (similar to `TelemetryLogStore`) to save and retrieve `PendingIncident` metadata to disk.
- Store incidents in `/files/pending_incidents/`.

#### [SentinelService.kt](file:///M:/Study/Watch/Watch2Out/wear/src/main/kotlin/com/jinn/watch2out/wear/service/SentinelService.kt)
- **Persistence on Failure**: Update `transferIncidentAssets` to save the incident to `IncidentAssetStore` if the immediate retries fail.
- **Periodic Retry Task**: Add a coroutine job that runs every 5 minutes (or when monitoring starts) to check `IncidentAssetStore` for pending items and attempt to re-transfer them.
- **Immediate Retry on Sync**: Trigger a check for pending assets whenever a full telemetry sync is requested (as this implies a working connection).

#### [WatchMessageService.kt](file:///M:/Study/Watch/Watch2Out/wear/src/main/kotlin/com/jinn/watch2out/wear/service/WatchMessageService.kt)
- Trigger a "Force Retry" of pending assets when the watch gains connection capability.

---

## Verification Plan

### Manual Verification
- Deploy to Wear device.
- **Simulate Disconnection**: Turn off Bluetooth/WiFi on the watch.
- **Trigger Incident**: Inject 15G crash data.
- **Verify Persistence**: Check logs for: `📦 Failed to transfer immediately. Saved to Fail-Safe Storage.`
- **Restore Connection**: Re-enable Bluetooth/WiFi.
- **Verify Retry**: Wait up to 5 minutes or trigger a manual sync. Check logs for: `🔄 Retrying transfer for pending incident: ...` and finally `✅ Success`.
- **Verify Mobile Side**: Ensure the EDR and Audio appear in the phone's Downloads/watch2out folder.
