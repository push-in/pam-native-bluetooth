<?php
declare(strict_types=1);namespace Pam\Native\Bluetooth;final readonly class BluetoothEvent{public function __construct(public BluetoothEventKind$kind,public string$deviceId='',public string$service='',public string$characteristic='',public string$valueBase64='',public string$message=''){}public function bytes():string{return base64_decode($this->valueBase64,true)?:'';}}
