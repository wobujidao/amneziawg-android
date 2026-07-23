# Telemetry disclosure — Mayak Networks Android client

Feature added 2026-07-23 (app 0.3.46 / versionCode 93 line). This document is the source of truth for
the web privacy policy + Google Play **Data safety** form. No UI, no user prompt — the beacon is silent.

## What the app sends

Two places send telemetry, both over the **same authenticated TLS channel** as the existing
`/v1/client/connect` call (`Authorization: Bearer <session token>`, cert-pinned host, domain→IP failover):

### 1. App version on connect (`POST /v1/client/connect`)
On every connection the request now includes `app_version` (the app's own version name, e.g. `0.3.46`).
Previously this arrived empty. Nothing else about the connect request changed.

### 2. Silent weekly beacon (`POST /v1/client/telemetry` → HTTP 204)
A `WorkManager` periodic job runs **once every 7 days** (network-connected constraint) and POSTs this
exact JSON — and nothing more:

```json
{
  "app_version":   "0.3.46",        // app version name  (BuildConfig.VERSION_NAME)
  "version_code":  93,              // app build number  (BuildConfig.VERSION_CODE)
  "device_model":  "Google Pixel 8",// Build.MANUFACTURER + " " + Build.MODEL
  "os_version":    "android 14",    // "android " + Build.VERSION.RELEASE
  "locale":        "ru-RU",         // current UI locale tag (BCP-47), "" if unknown
  "install_source":"Play",          // store installer: "Play", raw installer pkg, or "" (sideload)
  "connect_count": 5,               // cumulative count of successful connections (aggregate)
  "active_days":   3                // cumulative count of distinct days with a connection (aggregate)
}
```

Field types are fixed: strings for `app_version` / `device_model` / `os_version` / `locale` /
`install_source`; integers for `version_code` / `connect_count` / `active_days`.

## What is NOT sent
- **No user identifier and no IP address are sent by the app.** The server derives `user_id` and `ip`
  itself from the authenticated session token; the app never puts them in the body.
- No advertising ID, no device serial/IMEI/MAC, no precise or approximate location, no contacts, no
  browsing/traffic contents, no per-app usage, no free-form text.
- `connect_count` / `active_days` are **aggregate counters only** (a running total and a day count) —
  not timestamps, not a history, not per-session records. They are kept in local SharedPreferences and
  incremented on a successful connection; the day comparison uses only the local calendar date
  (`yyyy-MM-dd`, no time).

## Behaviour / safety
- **Silent**: no notification, no dialog, no visible effect whatsoever.
- **Only when signed in**: if there is no session token (user not logged in), the worker no-ops.
- **Best-effort**: any failure (no network, core unreachable, HTTP error) is swallowed — the worker
  always reports success, never retry-storms, and simply tries again at the next 7-day interval.
- **Frequency**: at most once per 7 days per install (`ExistingPeriodicWorkPolicy.KEEP`).

## Draft copy for the privacy policy + Play Data Safety
> Mayak Networks collects a small amount of non-personal diagnostic and analytics data to understand
> which app versions, devices, and operating systems are in use and to gauge overall engagement. Once a
> week the app sends its version and build number, the device model, the OS version, the interface
> locale, the install source, and two aggregate usage counters (the total number of successful
> connections and the number of distinct days the VPN was used). This data does not include your name,
> email, IP address, precise location, advertising identifier, or any browsing or traffic content, and
> it is never sold or shared with third parties — it is used solely for internal analytics and product
> improvement. The account identifier associated with the report is derived on our server from your
> authenticated session, not transmitted by the app. Telemetry runs silently and only while you are
> signed in.

### Play Data Safety mapping (for the form)
- **Data collected → App info and performance**: *App version* → yes (app version, build number, OS
  version, device model). Purpose: **Analytics**. Not shared. Collected, not "required" (optional).
- **Data collected → App activity**: *Other app-generated content / usage counts* → aggregate connection
  count and active-day count. Purpose: **Analytics**. Not shared.
- **Device or other IDs**: **No** (no advertising ID / device ID is sent by the app).
- **Location**: **No**. **Personal info (name/email)**: **No** (not in the beacon). **Financial**: No.
- **Data is encrypted in transit**: **Yes** (HTTPS/TLS). **Users can request deletion**: per account
  policy (server-side, tied to the account).
