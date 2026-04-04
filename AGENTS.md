# AGENTS.md (v27.6) - Watch² Out Safety-Critical Rules

This document defines non-negotiable standards for the Watch² Out system. Android Studio agents MUST adhere to these rules for all code generation and architectural suggestions.

## 1. Core Principles (The "Gold Standard")
- **Logic Centralization**: CrashScore and FSM logic reside **ONLY** in `:shared`.
- **Pure Functions**: `CrashScoreCalculator` must be a pure function: `(Inputs + Config + Confidence) -> Result`. No side effects or state.
- **One-Way UI**: `:wear` and `:app` consume `:shared` models. Never duplicate `:shared` logic.
- **Zero-Bypass**: No Boolean flags or "shortcuts" to bypass FSM states.

## 2. Module Responsibilities
- **:wear (Sentinel)**: High-frequency sensing, dynamic circular buffering (EDR), local alerts.
- **:app (Companion)**: Persistent storage, analytics, cloud/SMS relay, high-res dashboard.
- **:shared (Core)**: FSM, CrashScore, Protocol Contracts, and platform-agnostic models.

## 3. Telemetry & Logging Policy
- **Live Stream**: Ephemeral. 5–10Hz during active dashboard. Drop data if disconnected.
- **Telemetry Logs (Historical)**:
    - **Trigger**: `isTelemetryLoggingEnabled`.
    - **Persistence**: Batch and save to local disk every 60 seconds.
    - **Transfer**: Upload to `:app` every **1–2 hours**.
    - **Cleanup**: Delete from Wear storage **ONLY AFTER** confirmed successful transfer via `MessageClient`.
- **Incident Data**: Critical priority. Immediate transmission with indefinite retries.

## 4. CrashScore & FSM Rules
- **FSM States (EXACT)**: `IDLE` → `MOVING` → `PRE_EVENT` → [`FALLING` | `IMPACT`] → `POST_MOTION` → `STILLNESS` → `WAIT_CONFIRM` → `CONFIRMED_CRASH`.
- **Rule Constraints**: Only 3 core rules allowed in `CrashScore`:
    1. **Weak Suppression**: Low accel + low gyro + low Δv → score reduction.
    2. **Falling Pattern**: Low-G + pressure drop → boost.
    3. **Impact Combination**: High-G + rotation OR High-G + speed drop → boost.
- **Sensor Weights**: Accel (30%), Speed (25%), Gyro (15%), Pressure (10%), Stillness (15%), Roll (5%). Renormalize if sensors are missing.

## 5. Adaptive Sampling (Battery vs. Precision)
Rates MUST adapt to GPS speed with hysteresis (±5km/h) and a 2s cooldown:
- **0–10 km/h**: 400–500ms (Power Save).
- **10–30 km/h**: 200ms.
- **30–80 km/h**: 100–200ms.
- **80+ km/h**: 50–100ms (High-Res EDR).

## 6. Safety-Critical Coding Standards
- **Memory**: No object allocation in `onSensorChanged`. Use pre-allocated primitive arrays.
- **Null Safety**: **Strictly NO `!!` or `lateinit`** in `SentinelService`. Use `?` or `requireNotNull`.
- **Concurrency**: Use `serviceScope` (SupervisorJob + Dispatchers.Default).
- **Audio Evidence**: 10s recording starts on `IMPACT`. Delete if alert is dismissed by user.

## 7. AI Context Rules
- **Module Tagging**: Every snippet MUST start with `// [Module: :wear]`, `// [Module: :app]`, or `// [Module: :shared]`.
- **Search First**: Before editing a symbol, use `find_declaration` to ensure it's not a `:shared` core component.
