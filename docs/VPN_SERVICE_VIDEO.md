# Sprig 0.1.2 VpnService demonstration

Recorded and verified 2026-11-20 on a physical Pixel 7a, API 37.

Public player: https://mrmaderatoryt.github.io/Sprig/vpn-service-0.1.2.html

- Installed the existing release-signed 0.1.2 Preview APK (versionCode 3), retaining app data.
- Captured full disclosure, Cancel with capture OFF, app reopening, repeated disclosure, explicit agreement, Android system VPN consent, active VPN, fixture traffic and explicit stop.
- Selected only com.ccs.sprig.fixture. The explicitly started private LAN fixture completed 20/20 requests, zero errors; fixture reported VPN transport active. Sprig displayed 20 successful HTTP responses. No public targets were tested.
- Restored the initial app locale override (empty), stopped capture and the fixture server.
- Continuous normal-speed footage, no time cuts, no audio. English WebVTT explains the steps; the Android system consent dialog is Ukrainian.
- Public MP4: H.264, 720×1600, 12 fps, 70.083333 seconds, 1,972,136 bytes. Decoded fully with ffmpeg without errors. Local address and temporary fixture token masked; no app inventory or personal notifications shown.
- MP4 SHA-256: `ac09ef92f45e80c34deb005ba697118e97efc36dc42e11406d77e8b94e2ac7b3`. Raw footage remains local under ignored dist/, and is not published.

This is device verification of the demonstrated consent/capture flow, not comprehensive protocol, performance or Google Play policy validation. No application code or version changed for this task.
