# PAM Native Bluetooth

Production-oriented Bluetooth Low Energy for PAM Native. Scan by service UUID, connect, discover services, read and write characteristics, subscribe to notifications, and consume a bounded native event queue that survives PHP request boundaries.

```bash
composer require pushinbr/pam-native-bluetooth
pam mobile prepare
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
