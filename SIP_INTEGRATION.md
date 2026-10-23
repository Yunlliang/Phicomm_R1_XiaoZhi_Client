# R1 XiaoZhi SIP integration

This `feature/sip` branch is experimental. SIP registration and calls are not implemented yet.

Target: Android 5.1.1 / API 22, Rockchip RK3229, armeabi-v7a.

## Privacy

Do not hardcode any user's SIP domain, account or password. The R1 Web settings page must accept host/domain, SIP port, extension, authorization username and password. These values remain local to the device and must not be included in logs or status JSON. SIP starts disabled.

## Security prerequisite

The existing `WebConsole.java` describes unauthenticated LAN HTTP endpoints, potentially mirrored to port 8080. Before adding SIP credentials to this console, implement authenticated administration, CSRF defenses and a secure transport/trusted access boundary. Never return stored SIP password in a GET response.

## Planned implementation

1. Validate and store user-entered configuration locally.
2. Secure Web account settings and local save/enable controls.
3. Integrate API 22 / armeabi-v7a-compatible PJSIP registration and calls.
4. Share existing XiaoZhi microphone path, prioritize calls and restore music/assistant afterward.
5. Device-side tests and dedicated APK build.

Keep `main` unchanged.
