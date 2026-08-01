<?php
declare(strict_types=1);namespace Pam\Native\Bluetooth;enum BluetoothEventKind:int{case DeviceFound=1;case Connected=2;case Disconnected=3;case Services=4;case Value=5;case WriteCompleted=6;case Error=7;}
