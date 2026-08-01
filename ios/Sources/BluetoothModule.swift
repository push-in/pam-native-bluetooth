import CoreBluetooth
import Foundation
import PamNative

public final class BluetoothModule: NSObject, NativeModule, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    private lazy var central = CBCentralManager(delegate: self, queue: queue)
    private let queue = DispatchQueue(label: "dev.pam.bluetooth")
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var events: [[String: Any]] = []
    private var scanTimer: DispatchSourceTimer?

    public override init() { super.init(); _ = central }
    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        queue.async { do { let v = try WireMap.decode(payload); switch method {
        case "state": try self.success(["state": .integer(Int64(self.state()))], completion)
        case "scan": try self.scan(v); try self.success([:], completion)
        case "stopScan": self.stopScan(); try self.success([:], completion)
        case "connect": try self.peripheral(v).delegate = self; self.central.connect(try self.peripheral(v)); try self.success([:], completion)
        case "disconnect": self.central.cancelPeripheralConnection(try self.peripheral(v)); try self.success([:], completion)
        case "read": let (p, c) = try self.characteristic(v); p.readValue(for: c); try self.success([:], completion)
        case "write": let (p, c) = try self.characteristic(v); guard let data = Data(base64Encoded: try v.text("valueBase64")), data.count <= 512 else { throw BLEError.invalid }; p.writeValue(data, for: c, type: (try v.flag("response")) ? .withResponse : .withoutResponse); try self.success([:], completion)
        case "subscribe": let (p, c) = try self.characteristic(v); p.setNotifyValue(try v.flag("enabled"), for: c); try self.success([:], completion)
        case "poll": let limit = min(256, max(1, Int(try v.integer("limit")))); let rows = Array(self.events.prefix(limit)); self.events.removeFirst(min(limit, self.events.count)); let data = try JSONSerialization.data(withJSONObject: rows); try self.success(["json": .text(String(data: data, encoding: .utf8) ?? "[]")], completion)
        default: throw BLEError.method }
        } catch { self.failure(error.localizedDescription, completion) } }
    }
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {}
    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) { peripherals[peripheral.identifier] = peripheral; event(1, peripheral, message: peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? ""), extra: ["rssi": RSSI.intValue]) }
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) { peripheral.delegate = self; event(2, peripheral); peripheral.discoverServices(nil) }
    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { event(7, peripheral, message: error?.localizedDescription ?? "Connection failed.") }
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) { event(error == nil ? 3 : 7, peripheral, message: error?.localizedDescription ?? "") }
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) { if let error { event(7, peripheral, message: error.localizedDescription); return }; peripheral.services?.forEach { peripheral.discoverCharacteristics(nil, for: $0) }; event(4, peripheral, message: String(data: (try? JSONSerialization.data(withJSONObject: peripheral.services?.map { $0.uuid.uuidString } ?? [])) ?? Data("[]".utf8), encoding: .utf8) ?? "[]") }
    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) { if let error { event(7, peripheral, message: error.localizedDescription) } else { event(5, peripheral, service: characteristic.service?.uuid.uuidString ?? "", characteristic: characteristic.uuid.uuidString, value: characteristic.value?.base64EncodedString() ?? "") } }
    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) { event(error == nil ? 6 : 7, peripheral, service: characteristic.service?.uuid.uuidString ?? "", characteristic: characteristic.uuid.uuidString, message: error?.localizedDescription ?? "") }
    private func state() -> Int { switch central.state { case .poweredOff, .resetting: return 2; case .poweredOn: return 3; case .unauthorized: return 4; case .unsupported: return 5; default: return 1 } }
    private func scan(_ v: [String: WireValue]) throws { guard central.state == .poweredOn else { throw BLEError.unavailable }; let names = try JSONDecoder().decode([String].self, from: Data(try v.text("services").utf8)); stopScan(); central.scanForPeripherals(withServices: names.map(CBUUID.init(string:))); let timer = DispatchSource.makeTimerSource(queue: queue); timer.schedule(deadline: .now() + .milliseconds(Int(try v.integer("timeoutMillis")))); timer.setEventHandler { [weak self] in self?.stopScan() }; scanTimer = timer; timer.resume() }
    private func stopScan() { scanTimer?.cancel(); scanTimer = nil; central.stopScan() }
    private func peripheral(_ v: [String: WireValue]) throws -> CBPeripheral { guard let id = UUID(uuidString: try v.text("deviceId")), let p = peripherals[id] else { throw BLEError.device }; return p }
    private func characteristic(_ v: [String: WireValue]) throws -> (CBPeripheral, CBCharacteristic) { let p = try peripheral(v); let service = CBUUID(string: try v.text("service")); let characteristic = CBUUID(string: try v.text("characteristic")); guard let c = p.services?.first(where: { $0.uuid == service })?.characteristics?.first(where: { $0.uuid == characteristic }) else { throw BLEError.characteristic }; return (p, c) }
    private func event(_ kind: Int, _ p: CBPeripheral? = nil, service: String = "", characteristic: String = "", value: String = "", message: String = "", extra: [String: Any] = [:]) { if events.count >= 256 { events.removeFirst() }; var row: [String: Any] = ["kind": kind, "deviceId": p?.identifier.uuidString ?? "", "service": service, "characteristic": characteristic, "valueBase64": value, "message": message]; extra.forEach { row[$0] = $1 }; events.append(row) }
    private func success(_ values: [String: WireValue] = [:], _ completion: ModuleCompletion) throws { completion(.success, try WireMap.encode(values)) }
    private func failure(_ message: String, _ completion: ModuleCompletion) { completion(.failure, Data(message.prefix(1024).utf8)) }
}
private enum BLEError: Error { case method, unavailable, device, characteristic, invalid }
private extension Dictionary where Key == String, Value == WireValue { func text(_ key: String) throws -> String { guard case let .text(v)? = self[key] else { throw BLEError.invalid }; return v }; func integer(_ key: String) throws -> Int64 { guard case let .integer(v)? = self[key] else { throw BLEError.invalid }; return v }; func flag(_ key: String) throws -> Bool { guard case let .flag(v)? = self[key] else { throw BLEError.invalid }; return v } }
