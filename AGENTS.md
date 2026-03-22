# AGENTS.md (v23.0 – Dynamic Adaptive Sampling & Advanced Hub)

This document defines **non-negotiable** coding, design, and behavior rules for the **Watch² Out** project.
All generated code must be safe, deterministic, and production-ready for a safety‑critical Wear OS application.

## 1. Project Overview & Multi-Module Structure

**Goal**
Develop a safety-critical accident detection system with high-fidelity telemetry and adaptive resource management.

**Enforced Project Structure**
1. **`:wear` (Wear OS Sentinel):** Real-time sensing, dynamic circular buffering (EDR), local SMS fallback.
2. **`:app` (Mobile Companion):** Unified Analytics Hub, multi-contact relay, remote configuration.
3. **`:shared` (Pure Kotlin):** Shared models, protocol contracts, and utility formatters.


*   **Version:** Name `1.1`, Code `2`.
*   **Target:** `minSdk 30` (Wear), `minSdk 26` (App), `targetSdk 34`.
*   **Safety:** Zero `!!` tolerance. Strict `null` handling. `SupervisorJob` for all services.

## 3. UI/UX Hierarchy & High-Res Dashboard

### 3.1 Wear OS UI (`:wear`)
*   **Main:** Large START/STOP. Sub-caption shows real-time FSM state (e.g., `DRIVING`).
*   **Dashboard (Live Only):** Condensed 2-column grid showing `ACC` (g), `SPD`, `CRS` (CrashScore), `ROL` (RollSum), and `PRΔ`.
*   **Alert:** Synchronized flashing UI with 15s/7s sequence.

### 3.2 Mobile UI (`:app`)
**Analytics Hub (Dashboard):**
1.  **Live Feed:** Real-time state, Speed, Directional G-forces, and Color-coded CrashScore (🟢 <0.3, 🟡 <0.7, 🔴 ≥0.7).
2.  **Overall Peak:** Lifetime max values (since manual reset) with precise timestamps.
3.  **Windowed Peak:** Selectable range (1m / 5m / 10m / 30m / 1h) using `STATUS_SYNC`.

## 4. Detection Strategy & Adaptive Sampling (v22.0+)

### 4.1 Dynamic Adaptive Sampling
Sensor sampling rates adjust automatically based on GPS speed to balance battery life and precision:
*   **0 ~ 30 km/h:** 400ms (Power Save).
*   **30 ~ 80 km/h:** 200ms (Standard).
*   **80+ km/h / Impact:** 100ms (High-Res EDR).
*   **Hysteresis:** 5 km/h margin and 2s cooldown to prevent rate flapping.

### 4.2 Advanced Analytics
*   **RollSum (Rollover):** Energy-based "Leaky Integrator". Threshold: 60°/s. Decays at 5%/cycle. Resets on 10m stillness, 360° loop, or session start.
*   **CrashScore:** Weighted RMS impact magnitude normalized to 0.0–1.0 range.
*   ** function clamp(x, min=0, max=1) = max(min, min(max, x))
*   1. score_accel = clamp( (accel_max - 5G) / (15G - 5G) )
*       └─ 5G under=0, 15G over=1
*   2. score_speed = if(speed_pre < 20km/h) 0
*      else clamp(Δv / 40km/h)
*   3. score_gyro = clamp( (gyro_rms_ratio - 1.5) / (4.0 - 1.5) )
*       └─ baseline ratio 1.5 under=0, 4 over=1
*   4. score_press = clamp( |Δpressure| / 2.5hPa )
*   5. score_still = if(user_input) 0
*      else clamp( (t_still - 3s) / (8s - 3s) )
*   6. score_rollover = clamp( roll_sum / 360° )

#### Sensor Availability & Adaptive Scoring (Mandatory)
*   **All devices auto-adapt to available sensors:**

*   1. Detect missing sensors at startup
*   2. **Zero-weight missing sensors**
*   3. **Auto-renormalize remaining weights**
*   4. Slight trigger threshold reduction (0.5G)
*
*   Example (Baro missing):
*   Original: 0.3A+0.25S+0.15G+0.1P+0.15T+0.05R
*   Adaptive: 0.33A+0.28S+0.17G+0.00P+0.17T+0.06R
*   **Accuracy impact: <1.5%** - Proven robust.
*

## 5. Communication Protocol

### 5.1 Real-time Sync (`STATUS_SYNC`)
*   **Ephemeral:** Dashboard streams at 10Hz only when active. 
*   **Heartbeat:** 2s background sync for state consistency.
*   **Urgency:** Always use `setUrgent()` for all status and alert messages.

### 5.2 Synchronized Cancellation
*   "I'M OK" on either device must trigger a `Broadcast` to immediately finish alert activities on **both** devices and return the Sentinel FSM to `MONITORING`.

## 6. Emergency & Privacy
*   **SMS and Call Only:** Email support removed for privacy and reliability.
1. **Path A (Relay):** Watch -> Phone (JSON/Audio). Phone sends SMS/Email.
2. **Path B (Standalone):** If Relay fails & `useWatchCellular` is ON, Watch sends SMS directly.
*   **Multi-Contact:** Supports a prioritized list of emergency contacts.
*   **Background Location:** Mandatory permission for GPS speed-based adaptive sampling.

### 6.1 EDR and Audio Evidence
*   **Audio Evidence:** standard CD quality Mono Audio recording with AAC
*   **EDR:** 100 ms fixed period sensing data records include timestamp when IMPACT_DETECTED

### 6.2 Dual-Path Dispatch

## 7. Vehicle Crash Inference State Machine (FSM)
*(Refer to Mermaid diagram in README.md for the current logic including IDLE recovery)*

## 8. Core Coding Standards (MISRA-Adapted)
* **Safety:** No `!!` or `lateinit` in logic/services. Use explicit null checks.
* **Memory:** No object allocation inside `onSensorChanged`. Use pre-allocated primitive arrays.
* **Threading:** Use `SupervisorJob` + `Dispatchers.Default`. Throttle UI updates to 10Hz.

## 9. Architectural Robustness & Isolation (Multi-Process Support)
1. **Layered Responsibility:**
    * Strictly separate the **Control Plane** (UI, Scheduling, Complications) from the **Execution Plane** (Hardware-intensive tasks like Audio Capture).
2. **Cross-Process Integrity:**
    * Never assume SharedPreferences cache consistency across processes. Use event-driven triggers (Broadcasts) or real-time IPC (Messenger) for state synchronization.
    * Explicitly handle IPC-related exceptions (`RemoteException`, `DeadObjectException`) to ensure app stability.
3. **SOLID:**
    * Keep SOLID architecture rule when design and implementation.

## 10. Behavioral Instructions for AI (Strict Enforcement)

1. **Module Header:** Every code block MUST start with `// [Module: :app]` or `// [Module: :wear]`.
2. **Gradle First:** When initializing the project, provide `build.gradle.kts` for both modules simultaneously to ensure library alignment.
3. **Data Consistency:** Shared data models (JSON) must be identical in both modules.

## 11. Strict Safety Rules
**No !!**: Use ?.let or explicit null checks.
**No late init in Logic**: Strictly forbidden in Services/Detectors.
**Concurrency**: Use SupervisorJob + CoroutineScope. No GlobalScope.
**Resource Cleanup**: Explicitly unregister sensors/receivers in onDestroy().

## 12. Documentation
**Language**: Professional English.
**KDoc**: Required for all Public APIs.
**Rationale**: Comments must explain why a specific threshold was chosen (e.g., physics formulas).
