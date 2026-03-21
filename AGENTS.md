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

## 5. Communication Protocol

### 5.1 Real-time Sync (`STATUS_SYNC`)
*   **Ephemeral:** Dashboard streams at 10Hz only when active. 
*   **Heartbeat:** 2s background sync for state consistency.
*   **Urgency:** Always use `setUrgent()` for all status and alert messages.

### 5.2 Synchronized Cancellation
*   "I'M OK" on either device must trigger a `Broadcast` to immediately finish alert activities on **both** devices and return the Sentinel FSM to `MONITORING`.

## 6. Emergency & Privacy
*   **SMS Only:** Email support removed for privacy and reliability.
*   **Multi-Contact:** Supports a prioritized list of emergency contacts.
*   **Background Location:** Mandatory permission for GPS speed-based adaptive sampling.

## 7. Vehicle Crash Inference State Machine (FSM)
*(Refer to Mermaid diagram in README.md for the current logic including IDLE recovery)*
