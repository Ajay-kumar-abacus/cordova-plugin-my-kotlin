import Foundation
import CoreLocation
import UIKit
import Network

@objc(MyKotlinPlugin)
class MyKotlinPlugin: CDVPlugin {

    private var callbackIdKeep: String?
    private var locationManager: CLLocationManager?
    private var isTracking = false
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var monitor: NWPathMonitor?
    private let monitorQueue = DispatchQueue(label: "com.myplugin.networkmonitor")
    private var offlineQueue: [[String: Any]] = []
    private let offlineFileName = "offline_locations.json"
    
    private var userId: String?
    private var startTime: String?
    private var sendTimer: Timer?
    private var lastKnownLocation: CLLocation?
    private var lastSentLocation: CLLocation?
    private var isOnline = true
    private var flushInProgress = false
    private var isAppInForeground = true
    
    private let sendIntervalSeconds: TimeInterval = 30 // More frequent for sales reps
    private let apiUrl = "https://dev.basiq360.com/hosper_electrical/BackgroundLocation/saveGeoLocation"
    private let maxOfflineLocations = 1000
    
    // For sales tracking: Balance accuracy vs battery
    private let minDistanceFilter: CLLocationDistance = 30 // 30 meters - tracks shop visits
    private let minSendDistance: CLLocationDistance = 25 // Only send if moved 25m+ from last sent

    // MARK: - Init & App State Monitoring
    override func pluginInitialize() {
        super.pluginInitialize()
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
    }
    
    @objc func appWillEnterForeground() {
        isAppInForeground = true
        print("🟢 App entered foreground")
        startSendTimer()
        flushOfflineQueue()
    }
    
    @objc func appDidEnterBackground() {
        isAppInForeground = false
        print("🔴 App entered background - GPS will be reduced to save battery")
        stopSendTimer()
        beginBackgroundTask()
        
        // Reduce GPS accuracy in background to save battery
        locationManager?.desiredAccuracy = kCLLocationAccuracyBest
    }

    // MARK: - Start Tracking
    @objc(startTracking:)
    func startTracking(command: CDVInvokedUrlCommand) {
        self.callbackIdKeep = command.callbackId

        if let data = command.argument(at: 0) as? [String: Any] {
            self.userId = data["userId"] as? String
            self.startTime = data["startTime"] as? String
        }

        DispatchQueue.main.async {
            self.setupLocationManager()
            self.startNetworkMonitor()
            self.loadOfflineQueue()
            self.startLocationUpdates()
            self.startSendTimer()
            
            let msg = "✅ Tracking started for \(self.userId ?? "user")"
            self.sendEventToJS(event: "tracking_started", data: ["message": msg])
            print(msg)
        }

        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "tracking_started")
        result?.setKeepCallbackAs(true)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }

    @objc(stopTracking:)
    func stopTracking(command: CDVInvokedUrlCommand) {
        stopLocationUpdates()
        stopNetworkMonitor()
        stopSendTimer()
        endBackgroundTask()
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "tracking_stopped")
        self.commandDelegate.send(result, callbackId: command.callbackId)
        print("🛑 Tracking stopped")
    }

    // MARK: - Location Setup
    private func setupLocationManager() {
        if locationManager != nil { return }
        
        let lm = CLLocationManager()
        lm.delegate = self
        
        // For sales: Accurate GPS tracking when moving
        lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        lm.allowsBackgroundLocationUpdates = true
        lm.pausesLocationUpdatesAutomatically = false
        lm.activityType = .automotiveNavigation
        lm.distanceFilter = minDistanceFilter
        
        locationManager = lm

        let status = CLLocationManager.authorizationStatus()
        if status == .notDetermined {
            lm.requestAlwaysAndWhenInUseAuthorization()
        } else if status == .authorizedWhenInUse {
            lm.requestAlwaysAuthorization()
        }
    }

    private func startLocationUpdates() {
        guard let lm = locationManager else { return }
        if isTracking { return }
        
        isTracking = true
        beginBackgroundTask()
        
        // Primary: Continuous GPS updates (for foreground & light background)
        lm.startUpdatingLocation()
        
        // Secondary: Significant location changes (backup for heavy background)
        lm.startMonitoringSignificantLocationChanges()
        
        print("📍 Location tracking started")
    }

    private func stopLocationUpdates() {
        guard let lm = locationManager else { return }
        if !isTracking { return }
        
        lm.stopUpdatingLocation()
        lm.stopMonitoringSignificantLocationChanges()
        isTracking = false
        
        print("📍 Location tracking stopped")
    }

    // MARK: - Background Task Management
    private func beginBackgroundTask() {
        if backgroundTask != .invalid { return }
        
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "SalesTracking") { [weak self] in
            print("⚠️ Background task expiring - flushing queue")
            self?.flushOfflineQueue()
            self?.endBackgroundTask()
        }
        
        print("🔄 Background task started")
    }

    private func endBackgroundTask() {
        if backgroundTask == .invalid { return }
        
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    // MARK: - Network Monitor
    private func startNetworkMonitor() {
        if monitor != nil { return }
        
        monitor = NWPathMonitor()
        monitor?.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            
            let wasOffline = !self.isOnline
            self.isOnline = path.status == .satisfied
            
            if self.isOnline && wasOffline {
                print("🌐 Internet restored - flushing offline queue")
                self.flushOfflineQueue()
            } else if !self.isOnline {
                print("🌐 Internet lost - saving to offline queue")
            }
        }
        monitor?.start(queue: monitorQueue)
    }

    private func stopNetworkMonitor() {
        monitor?.cancel()
        monitor = nil
    }

    // MARK: - Offline Queue Management
    private func saveOffline(_ payload: [String: Any]) {
        if offlineQueue.count >= maxOfflineLocations {
            offlineQueue.removeFirst()
        }
        offlineQueue.append(payload)
        persistOfflineQueue()
        
        let count = offlineQueue.count
        print("💾 Offline saved (\(count) total in queue)")
        sendEventToJS(event: "offline_saved", data: ["queue_size": count, "status": "offline"])
    }

    private func persistOfflineQueue() {
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        
        let fileURL = dir.appendingPathComponent(offlineFileName)
        
        do {
            let data = try JSONSerialization.data(withJSONObject: offlineQueue)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("❌ Error persisting offline queue:", error)
        }
    }

    private func loadOfflineQueue() {
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        
        let fileURL = dir.appendingPathComponent(offlineFileName)
        
        do {
            if FileManager.default.fileExists(atPath: fileURL.path) {
                let data = try Data(contentsOf: fileURL)
                if let arr = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                    offlineQueue = arr
                    print("📂 Loaded \(arr.count) offline locations")
                }
            }
        } catch {
            print("❌ Error loading offline queue:", error)
        }
    }

    private func flushOfflineQueue() {
        guard !flushInProgress && offlineQueue.count > 0 else { return }
        
        flushInProgress = true
        let pending = offlineQueue
        offlineQueue.removeAll()
        persistOfflineQueue()
        
        print("🔄 Flushing \(pending.count) locations")
        
        var successCount = 0
        var failedCount = 0
        let group = DispatchGroup()
        
        for payload in pending {
            group.enter()
            sendToServer(payload) { success in
                defer { group.leave() }
                if success {
                    successCount += 1
                } else {
                    failedCount += 1
                    self.offlineQueue.append(payload)
                }
            }
        }
        
        group.notify(queue: .main) { [weak self] in
            self?.persistOfflineQueue()
            self?.flushInProgress = false
            
            print("✅ Flush complete: Sent=\(successCount), Failed=\(failedCount)")
            self?.sendEventToJS(event: "flush_complete", data: [
                "sent": successCount,
                "failed": failedCount,
                "status": "flushed"
            ])
        }
    }

    // MARK: - Send Timer (Foreground Only)
    private func startSendTimer() {
        guard isAppInForeground else { return }
        
        stopSendTimer()
        
        DispatchQueue.main.async {
            self.sendTimer = Timer.scheduledTimer(withTimeInterval: self.sendIntervalSeconds, repeats: true) { [weak self] _ in
                self?.periodicSend()
            }
            RunLoop.current.add(self.sendTimer!, forMode: .common)
        }
    }

    private func stopSendTimer() {
        sendTimer?.invalidate()
        sendTimer = nil
    }

    private func periodicSend() {
        guard let loc = lastKnownLocation else { return }
        
        // Send every 30 seconds continuously - no distance check needed
        let payload = buildPayload(from: loc)
        
        if isOnline {
            sendToServer(payload) { success in
                if success {
                    self.lastSentLocation = loc
                    self.sendEventToJS(event: "location_sent", data: ["status": "sent"])
                } else {
                    self.saveOffline(payload)
                }
            }
        } else {
            saveOffline(payload)
        }
    }

    // MARK: - API Send
    private func sendToServer(_ payload: [String: Any], completion: ((Bool) -> Void)? = nil) {
        guard let url = URL(string: apiUrl) else {
            completion?(false)
            return
        }
        
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 10
        
        do {
            req.httpBody = try JSONSerialization.data(withJSONObject: payload)
        } catch {
            print("❌ Payload error:", error)
            completion?(false)
            return
        }

        URLSession.shared.dataTask(with: req) { _, resp, err in
            if let err = err {
                print("❌ Send error:", err.localizedDescription)
                completion?(false)
                return
            }
            
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 500
            let success = code >= 200 && code < 300
            completion?(success)
        }.resume()
    }

    // MARK: - Helpers
    private func buildPayload(from loc: CLLocation) -> [String: Any] {
        return [
            "userId": userId ?? "",
            "startTime": startTime ?? "",
            "latitude": loc.coordinate.latitude,
            "longitude": loc.coordinate.longitude,
            "accuracy": loc.horizontalAccuracy,
            "speed": loc.speed,
            "timestamp": ISO8601DateFormatter().string(from: loc.timestamp),
            "provider": "ios",
            "altitude": loc.altitude,
            "isBackground": !isAppInForeground
        ]
    }

    private func sendEventToJS(event: String, data: [String: Any]?) {
        guard let cb = callbackIdKeep else { return }
        
        var dict: [String: Any] = ["event": event]
        if let d = data { dict["data"] = d }
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: dict)
        result?.setKeepCallbackAs(true)
        self.commandDelegate.send(result, callbackId: cb)
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
    }
}

// MARK: - CLLocationManagerDelegate
extension MyKotlinPlugin: CLLocationManagerDelegate {
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        
        lastKnownLocation = loc
        print("📍 Location: \(String(format: "%.4f", loc.coordinate.latitude)), \(String(format: "%.4f", loc.coordinate.longitude)) | Online: \(isOnline)")

        // Send immediately on each location update (continuous)
        let payload = buildPayload(from: loc)
        
        if isOnline {
            sendToServer(payload) { success in
                if success {
                    self.sendEventToJS(event: "location_sent", data: ["status": "sent"])
                } else {
                    self.saveOffline(payload)
                }
            }
        } else {
            saveOffline(payload)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("❌ Location error:", error.localizedDescription)
        sendEventToJS(event: "location_error", data: ["error": error.localizedDescription])
    }
    
    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        switch status {
        case .authorizedAlways:
            print("✅ Always authorization granted")
            if isTracking { startLocationUpdates() }
        case .authorizedWhenInUse:
            print("⚠️ Only when-in-use auth. Requesting always...")
            manager.requestAlwaysAuthorization()
        case .denied, .restricted:
            print("❌ Authorization denied")
            stopLocationUpdates()
            sendEventToJS(event: "auth_denied", data: ["status": "denied"])
        case .notDetermined:
            print("⏳ Authorization pending")
        @unknown default:
            break
        }
    }
}