# Walkthrough - Incident Detection & FSM Fixes

I have successfully resolved the issue where certain crash scenarios were not triggering incidents. The fixes involved centralizing logic, correcting sensor units, and refining detection parameters.

## Key Changes

### 1. Centralized & Robust FSM
Created [IncidentFsm.kt](file:///M:/Study/Watch/Watch2Out/shared/src/main/kotlin/com/jinn/watch2out/shared/util/IncidentFsm.kt) in the `:shared` module.
- **Fixed "State Bounce"**: Added hysteresis to the `PRE_EVENT` state. The FSM now "latches" in `PRE_EVENT` as long as G-force remains elevated (>3.0G), preventing it from prematurely returning to `MOVING` and allowing the scoring algorithm to catch sequential impacts.
- **Improved Transitions**: Enhanced logic for `FALLING` and `STILLNESS` recovery.

### 2. Enhanced CrashScore Sensitivity
Updated [CrashScoreCalculator.kt](file:///M:/Study/Watch/Watch2Out/shared/src/main/kotlin/com/jinn/watch2out/shared/util/CrashScoreCalculator.kt):
- **Refined Rule 3**: Lowered the sensor fusion boost threshold to capture moderate-G events with high rotation (Rollovers/Side Impacts). Increased the boost to 0.30 to ensure valid incidents reach the alert threshold.

### 3. Refined Detection Parameters
Updated [WatchSettings.kt](file:///M:/Study/Watch/Watch2Out/shared/src/main/kotlin/com/jinn/watch2out/shared/model/WatchSettings.kt):
- **`accelThresholdG`**: Lowered from 10.0G to 8.0G for more reliable primary triggers.
- **`crashScoreThreshold`**: Lowered from 0.65 to 0.60 for optimal sensitivity.
- **`accelMinG`**: Kept at 2.5G to include moderate impacts in the score calculation.

### 4. SentinelService Reliability
Modified [SentinelService.kt](file:///M:/Study/Watch/Watch2Out/wear/src/main/kotlin/com/jinn/watch2out/wear/service/SentinelService.kt):
- **Fixed Manual Injection**: Removed legacy bypass logic that skipped the FSM. Manual injections now feed directly into the processing loop, ensuring they are validated by the full safety logic.
- **Fixed Audio Transfer Race Conditions**: Added `audioRecordingMutex` and `transferMutex` to prevent multiple concurrent recording/transfer operations. This resolves the `ASSET_UNAVAILABLE` errors caused by manual injections triggering overlapping alert sequences.
- **Corrected Gyro Units**: Added conversion between rad/s (hardware/manual input) and deg/s (internal scoring) to ensure consistency.
- **Synchronized Audio Filename**: Updated to `YYYYMMDD-HHMMSS-REC.aac` to match EDR logs.
- **Pretty Printing**: Enabled for all JSON outputs.

### 5. Mobile Companion Updates
Modified [MobileMessageService.kt](file:///M:/Study/Watch/Watch2Out/app/src/main/kotlin/com/jinn/watch2out/service/MobileMessageService.kt):
- **Flexible Asset Saving**: Updated to preserve the filename and extension sent from the Watch, preventing double extensions or hardcoded formats.

## Verification Results

### Automated Tests
- **IncidentFsm Unit Tests**: Created and ran unit tests to verify all state transitions (e.g., `IDLE` -> `MOVING`, `MOVING` -> `PRE_EVENT` -> `IMPACT`).
- **Command**: `gradlew :shared:testDebugUnitTest`
- **Result**: ✅ All 6 tests passed.

### Manual Verification (Simulated)
- Verified through Logcat that simulations now correctly trigger FSM transitions.
- **Audio Reliability**: Confirmed that overlapping manual injections are properly de-duplicated by the new mutex logic, and audio assets are transferred successfully without `ASSET_UNAVAILABLE` errors.
- **Audio Filename**: Confirmed Wear OS generates `20260426-135558-REC.aac` and Mobile saves it identically.
- **Alert UI**: Confirmed that the `WAIT_CONFIRM` state is reached at the end of valid sequences, triggering the countdown UI.

render_diffs(file:///M:/Study/Watch/Watch2Out/shared/src/main/kotlin/com/jinn/watch2out/shared/util/IncidentFsm.kt)
render_diffs(file:///M:/Study/Watch/Watch2Out/shared/src/main/kotlin/com/jinn/watch2out/shared/model/WatchSettings.kt)
render_diffs(file:///M:/Study/Watch/Watch2Out/wear/src/main/kotlin/com/jinn/watch2out/wear/service/SentinelService.kt)
render_diffs(file:///M:/Study/Watch/Watch2Out/app/src/main/kotlin/com/jinn/watch2out/service/MobileMessageService.kt)
