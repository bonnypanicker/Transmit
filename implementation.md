Implementation Plan

Concrete Improvements For Reliability
1) Timing recovery: derive symbol boundaries from transitions rather than fixed delays
2) Exposure control: lock exposure/ISO when supported, otherwise adapt thresholds per window
3) Preamble lock: sliding correlation to detect preamble and resync on loss
4) Symbol integration: aggregate multiple frames per half-bit to handle FPS jitter
5) Error handling: optional ACK/NAK or simple repeat on CRC failure
6) Ambient rejection: dynamic baseline tracking with hysteresis

Phases
Phase 1: Protocol, Encoding, CRC
Status: Done
Exit criteria:
- Manchester encode/decode utilities
- Byte/bit conversion utilities
- CRC implementation
- Packet build/parse helpers

Phase 2: Torch Transmission
Status: Done
Exit criteria:
- Torch controller wrapper
- Transmission scheduler with cancellation
- Integration of encoded symbols with torch toggles

Phase 3: Camera Capture And ROI Tracking
Status: Done
Exit criteria:
- CameraX analyzer setup
- Bright spot detection and ROI tracking
- Signal extraction for brightness values

Phase 4: Signal Processing And Decode
Status: Done
Exit criteria:
- Smoothing and adaptive threshold
- Symbol detection and Manchester decode
- Packet parsing and CRC validation

Phase 5: Adaptation Layer
Status: Done
Exit criteria:
- Bitrate controller based on signal quality
- Lock and resync logic

Phase 6: UI And Diagnostics
Status: Done
Exit criteria:
- Sender and receiver screens
- Status indicators and debug overlay
