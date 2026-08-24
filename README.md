<!-- pam:product-page:start -->
<div align="center">

# PAM Native Bluetooth

**Typed, lifecycle-safe Bluetooth LE from PHP.**

Discover peripherals, connect to GATT services, exchange characteristics, and receive notifications without exposing unbounded radio events to PHP.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-bluetooth?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-bluetooth)
[![CI](https://img.shields.io/github/actions/workflow/status/push-in/pam-native-bluetooth/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/push-in/pam-native-bluetooth/actions)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](https://push-in.github.io/pam-docs/native/overview/) · [Quick start](#quick-start) · [What you can build](#what-you-can-build) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-bluetooth/issues)**

</div>

---

## Why PAM Native Bluetooth

Discover peripherals, connect to GATT services, exchange characteristics, and receive notifications without exposing unbounded radio events to PHP. The public API is strictly typed for PHP 8.5; expensive or frame-sensitive work stays in Rust or the platform SDK instead of crossing the application boundary every frame.

| | |
| --- | --- |
| **Best for** | A focused capability you can add to any PAM Native application |
| **Native path** | Android Bluetooth LE · CoreBluetooth |
| **Application model** | Composer package + generated native integration |
| **Design rule** | Independent module; no feed, vertical, or application template bundled |

## What you can build

- Wearables and fitness sensors
- IoT provisioning and device control
- Medical, retail, and industrial BLE accessories

## Quick start

Already have a PAM Native project? Add only this capability:

```bash
pam composer require pushinbr/pam-native-bluetooth
pam doctor --fix
```

New to PAM? Follow the **[five-minute PAM Native setup](https://push-in.github.io/pam-docs/native/overview/)** once, then return here. Your application stays a normal Composer project with a committed lockfile.
<!-- pam:product-page:end -->

## See it in action

Production-oriented Bluetooth Low Energy for PAM Native. Scan by service UUID, connect, discover services, read and write characteristics, subscribe to notifications, and consume a bounded native event queue that survives PHP request boundaries.

```bash
pam add bluetooth
pam doctor
```

```php
$bluetooth->scan(['180d'], fn (bool $ok, ?string $error) => null);
$bluetooth->poll(function (array $events) use ($bluetooth) {
    foreach ($events as $event) {
        if ($event->kind === BluetoothEventKind::DeviceFound) {
            $bluetooth->connect($event->deviceId, fn () => null);
        }
    }
});
```

Android 12+ requires runtime grants for Nearby Devices. Android 11 and earlier may require location permission for discovery. iOS displays the Bluetooth purpose string supplied by this plugin. Device identifiers are opaque and must not be treated as MAC addresses.

The native queue holds at most 256 events and drops the oldest event under sustained backpressure. Applications should poll regularly and perform protocol framing, authentication, retry policy, and firmware-specific validation at the application layer.

## What installation does

`pam add bluetooth` resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation.

Use `pam packages` to inspect availability and `pam remove bluetooth` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

## API guide

| API | Responsibility |
| --- | --- |
| `Bluetooth` | Scan, connect, discover, read, write, subscribe, and poll. |
| `BluetoothEvent` | Normalized event payload with opaque device identifiers. |
| `BluetoothEventKind` | Typed device, connection, value, and failure events. |
| `BluetoothState` | Typed adapter and connection state. |

All coded states, kinds, and variants are sequential integer-backed enums. Use enum cases in application code; do not depend on raw wire numbers.

## Production checklist

- Stop scans promptly and disconnect devices no longer in use.
- Poll often enough to avoid pressure on the bounded 256-event queue.
- Implement device protocol framing, authentication, retry, and validation in the app.
- Run `pam doctor`, `pam test`, and a signed release build on every supported platform.
- Exercise denial, cancellation, backgrounding, process restart, and offline behavior before release.

## Troubleshooting

- **No devices found:** verify runtime permission, Bluetooth state, and advertised service UUIDs.
- **Connection drops:** handle disconnect events and use bounded backoff.
- **Missing notifications:** subscribe only after service discovery completes.
- **Native integration is stale:** run `pam doctor --fix`, rebuild the native host, and inspect the first reported diagnostic.

## Compatibility and support

This package targets PAM Native `0.8.x`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-bluetooth/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
