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

### 3. Push wake-up token (`POST /v1/client/push/register`) — added 2026-08-13
The app is an accelerator for the in-app message box (SPEC-0047), not a new data source. To be woken up
it registers a **delivery address** with our core:

```json
{ "token": "<FCM registration token>", "platform": "android", "app_version": "0.4.12" }
```

Three facts that matter for the policy and the Data Safety form:

- **A Google service is now in the path.** The token is issued by Firebase Cloud Messaging (Google), so
  Google necessarily knows that this device runs this app and relays our wake-ups to it. This is the one
  place where a third party is involved at all — see the corrected wording below.
- **The push itself carries NO content**: `{"kind":"mailbox","id":"<message id>"}` and nothing else.
  Message titles and bodies never leave our core over the Google path; the app fetches them from our own
  API and renders the notification itself. This is deliberate (a notification is read over the
  shoulder, and Android cannot be forced to hide its text on the lock screen).
- **Registration only happens when notifications are enabled** for the app and a user is signed in. On
  devices without Google Play services, or in non-production builds (no Firebase configuration is
  compiled in), nothing is registered and nothing is sent; the message box keeps working by polling.
  Signing out sends `POST /v1/client/push/unregister` with the same token.

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
>
> If notifications are enabled, the app also registers a push delivery address (a Firebase Cloud
> Messaging token) with our server so that we can notify you about your account — a payment receipt, an
> expiring subscription, a support reply. Push messages carry **no text**: they only tell the app to
> check its message box, and the notification you see is composed by the app from data fetched over our
> own encrypted connection. Delivery relies on Google Play services, which therefore relays the wake-up
> signal; the content of your messages is never handed to it. Turning notifications off removes the
> registration, and on devices without Google Play services the app simply checks for messages itself.

### Play Data Safety mapping (for the form)
- **Data collected → App info and performance**: *App version* → yes (app version, build number, OS
  version, device model). Purpose: **Analytics**. Not shared. Collected, not "required" (optional).
- **Data collected → App activity**: *Other app-generated content / usage counts* → aggregate connection
  count and active-day count. Purpose: **Analytics**. Not shared.
- **Device or other IDs**: **Yes, since 2026-08-13** — the FCM registration token (a per-install push
  delivery address) is sent to our server. Purpose: **App functionality** (notifications). Not shared
  for ads or analytics; the token exists only because Google Play services delivers the wake-up.
  Still **No** advertising ID, **No** device serial/IMEI/MAC.
  ⚠️ This line used to read "No". It must be updated in the Play form **before** an APK/AAB containing
  the Firebase SDK reaches users: the SDK obtains a token at first launch, so the declaration is wrong
  from the first production build, not from the day push messages start being sent.
- **Location**: **No**. **Personal info (name/email)**: **No** (not in the beacon). **Financial**: No.
- **Data is encrypted in transit**: **Yes** (HTTPS/TLS). **Users can request deletion**: per account
  policy (server-side, tied to the account).
