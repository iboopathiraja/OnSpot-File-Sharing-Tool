# OnSpot for Android

The Android half of [OnSpot](../README.md), built with Kotlin and Jetpack Compose. Minimum Android 7.0 (API 24).

## Build

1. Open this folder in Android Studio.
2. Run the `app` configuration.

To build a release, provide signing details through environment variables:

| Variable         | Meaning                                         |
|------------------|-------------------------------------------------|
| `KEYSTORE_PATH`  | path to your upload keystore                    |
| `STORE_PASSWORD` | keystore password                               |
| `KEY_PASSWORD`   | password for the `upload` key                   |

## Structure

```
app/src/main/java/com/beyondzero/onspot/
├── data/
│   ├── network/      NSD (mDNS) discovery and the UDP LanScanner
│   ├── transfer/     TCP server/client and the transfer queue
│   ├── settings/     name, avatar, connection mode and receive filter
│   └── FlowshareCore.kt   the long-lived core that the service and UI share
├── domain/model/     Device, TransferRecord, ClipboardState
├── presentation/
│   ├── home/         Home, Files, Devices, the device page and shared components
│   ├── intro/        opening animation
│   └── onboarding/   first-run setup
└── service/          foreground service so transfers continue in the background
```

## Permissions

| Permission                                        | Why                                                    |
|---------------------------------------------------|--------------------------------------------------------|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Talk to devices on the local network             |
| `CHANGE_WIFI_MULTICAST_STATE`                     | Receive mDNS discovery packets                         |
| `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`       | Keep transfers going, and show progress, when the app is closed |
| `READ/WRITE_EXTERNAL_STORAGE` (API ≤ 32)          | Save received files on older Android versions          |
