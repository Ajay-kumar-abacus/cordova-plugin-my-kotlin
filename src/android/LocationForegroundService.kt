package com.example.mykotlinplugin

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.net.ConnectivityManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.IntentFilter
import android.net.NetworkCapabilities
import android.provider.Settings
import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.app.PendingIntent
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.net.Uri
import java.util.concurrent.ConcurrentLinkedQueue
import java.io.File
import java.io.FileWriter
import java.io.FileReader
import java.io.BufferedReader
import com.google.android.gms.location.*
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.OnFailureListener
import java.util.concurrent.PriorityBlockingQueue
import java.util.Comparator
import java.util.concurrent.atomic.AtomicLong


class LocationForegroundService : Service(), LocationListener {
    
    private lateinit var locationManager: LocationManager  // Keep for GPS
private lateinit var fusedLocationClient: FusedLocationProviderClient  // Add for Fused
private lateinit var locationRequest: LocationRequest
private lateinit var fusedLocationCallback: LocationCallback
private var isExplicitlyStopped = false
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "LocationChannel"
    private var isServiceRunning = false
    private val executorService = Executors.newSingleThreadExecutor()
  private lateinit var sharedPreferences: SharedPreferences
private val EXTRA_DATA_KEY = "stored_extra_data"
private val PREFS_NAME = "LocationServicePrefs"

     private var lastKnownLocations = mutableListOf<LocationData>()
    private val MAX_LOCATION_HISTORY = 10
    private var currentActivity = "unknown"
    private var currentSpeed = 0.0f
    private var isMoving = false
    private val STILL_THRESHOLD = 2.0f
    private val WALKING_SPEED_MIN = 0.5f
    private val WALKING_SPEED_MAX = 2.5f
    private val VEHICLE_SPEED_MIN = 3.0f
    
    // Restart mechanism
    private lateinit var alarmManager: AlarmManager
    private lateinit var restartServicePI: PendingIntent
    private val RESTART_SERVICE_ID = 1002
    
    // Broadcast receivers for system events
    private var screenReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
private val offlineDataQueue = PriorityBlockingQueue<OfflineDataRecord>(
    1000, 
    Comparator<OfflineDataRecord> { o1, o2 -> 
        when {
            o1.priority != o2.priority -> o1.priority.compareTo(o2.priority)
            else -> o1.sequenceNumber.compareTo(o2.sequenceNumber)  // Sort by sequence number for proper order
        }
    }
)

private val MAX_OFFLINE_RECORDS = 1000
private val sequenceCounter = AtomicLong(0)
private var networkReceiver: BroadcastReceiver? = null
private var isInternetAvailable = false
private val OFFLINE_DATA_FILE = "offline_location_data.json"

private var lastLocationUpdateTime = 0L
private val FORCED_UPDATE_INTERVAL = 60000L // Force update every 60 seconds
private lateinit var forcedUpdateHandler: Handler
private var forcedUpdateRunnable: Runnable? = null

      data class LocationData(
        val location: Location,
        val timestamp: Long,
        val accuracy: Float
    )
    data class OfflineDataRecord(
    val timestamp: Long,
    val data: JSONObject,
    val priority: Int = 0, // 0 = offline (process first), 1 = real-time
    val sequenceNumber: Long,
    val dataType: String,
    val originalTimestamp: Long,
    val isOfflineData: Boolean = true
)
    
  override fun onCreate() {
    super.onCreate()
    android.util.Log.d("LocationService", "Service onCreate called")
    
    // Initialize SharedPreferences
    sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    createNotificationChannel()
    locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
setupLocationRequest()
setupFusedLocationCallback()
    setupRestartMechanism()
    setupSystemEventListeners()
    setupNetworkMonitoring()
    setupForcedPeriodicUpdates()
    loadOfflineDataFromFileSequential()
    isServiceRunning = true
    lastLocationUpdateTime = System.currentTimeMillis()
}
    
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    android.util.Log.d("LocationService", "Service onStartCommand called with action: ${intent?.action}")
    
    // CHECK FOR STOP ACTION FIRST - This must be the first check
    if (intent?.action == "STOP_SERVICE") {
        android.util.Log.d("LocationService", "Received STOP_SERVICE action")
        isExplicitlyStopped = true
        
        // Save stop state to preferences
        sharedPreferences.edit()
            .putBoolean("explicitly_stopped", true)
            .apply()
        
        // Stop the foreground notification
        stopForeground(true)
        
        // Stop the service
        stopSelf()
        
        // Return NOT_STICKY so service won't restart
        return START_NOT_STICKY
    }
    
    // Clear stop state when starting normally
    if (intent?.action != "STOP_SERVICE") {
        isExplicitlyStopped = false
        sharedPreferences.edit()
            .putBoolean("explicitly_stopped", false)
            .apply()
    }
    
    // Normal service start logic
    try {
        // Handle extra data if provided
        val extraDataString = intent?.getStringExtra("extraData")
        if (extraDataString != null && extraDataString.isNotEmpty()) {
            try {
                sharedPreferences.edit()
                    .putString(EXTRA_DATA_KEY, extraDataString)
                    .apply()
                android.util.Log.d("LocationService", "Stored extra data: $extraDataString")
            } catch (e: Exception) {
                android.util.Log.e("LocationService", "Error storing extra data: ${e.message}")
            }
        }
        
        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start location updates safely
        startLocationUpdatesSafely()
        
        // Schedule periodic health checks
        schedulePeriodicHealthCheck()
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error in onStartCommand: ${e.message}")
        // Service continues to run even if location setup fails
    }
    
    // Return STICKY for normal operation (will restart if killed)
    return START_STICKY
}
private fun setupForcedPeriodicUpdates() {
    forcedUpdateHandler = Handler(Looper.getMainLooper())
    
   forcedUpdateRunnable = object : Runnable {
    override fun run() {
        if (isServiceRunning) {
            try {
                val timeSinceLastUpdate = System.currentTimeMillis() - lastLocationUpdateTime
                
                if (timeSinceLastUpdate > FORCED_UPDATE_INTERVAL) {
                    android.util.Log.d("LocationService", "🔄 Time since last: ${timeSinceLastUpdate}ms")
                    forceLocationUpdate()
                }
            } catch (e: Exception) {
                android.util.Log.e("LocationService", "Error in forced update check: ${e.message}")
                // Still update the timestamp to prevent rapid retries
                lastLocationUpdateTime = System.currentTimeMillis() - (FORCED_UPDATE_INTERVAL / 2)
            } finally {
                // Always schedule next check
                forcedUpdateHandler.postDelayed(this, 30000)
            }
        }
    }
}
    
    // Start the periodic check
    forcedUpdateHandler.postDelayed(forcedUpdateRunnable!!, 30000)
}

private fun forceLocationUpdate() {
    android.util.Log.d("LocationService", "🔄 FORCING location update")
    
    // Always try to get location first if we have permission
    if (hasLocationPermission()) {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    android.util.Log.d("LocationService", "🎯 FORCED: Got location")
                    onLocationChanged(location)
                } else {
                    android.util.Log.d("LocationService", "🎯 FORCED: No location, sending device data")
                    sendDeviceDataWithoutLocation()
                }
            }.addOnFailureListener { error ->
                android.util.Log.e("LocationService", "🎯 FORCED: Failed - ${error.message}")
                sendDeviceDataWithoutLocation()
            }
        } catch (e: SecurityException) {
            android.util.Log.e("LocationService", "Security exception: ${e.message}")
            sendDeviceDataWithoutLocation()
        }
    } else {
        // No permission, but still send device data
        android.util.Log.d("LocationService", "No location permission, sending device data only")
        sendDeviceDataWithoutLocation()
    }
    
    // Reset the last update time regardless
    lastLocationUpdateTime = System.currentTimeMillis()
}
private fun sendDeviceDataWithoutLocation() {
    executorService.submit {
        try {
            android.util.Log.d("LocationService", "📊 Sending device data without location")
            val deviceData = createCompleteDataStructure(null, "device_data_only")
            
            sendToAPIWithSequencing(deviceData, "device_data_only")
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error sending device data: ${e.message}")
        }
    }
}

private fun startLocationUpdatesSafely() {
    android.util.Log.d("LocationService", "Starting location updates safely")
    
    // Check if location manager is available
    if (!::locationManager.isInitialized) {
        android.util.Log.e("LocationService", "LocationManager not initialized")
        return
    }
    
    // Check permissions before attempting to start
    val hasAnyLocationPermission = hasLocationPermission()
    
    if (hasAnyLocationPermission) {
        android.util.Log.d("LocationService", "Location permission available - starting updates")
        startLocationUpdates()
    } else {
        android.util.Log.w("LocationService", "No location permission - service running without location")
        // Service continues without location tracking
        // Still collect battery and device info periodically
        schedulePeriodicDataCollection()
    }
}
private fun schedulePeriodicDataCollection() {
    android.util.Log.d("LocationService", "Scheduling periodic data collection without location")
    
    // Collect device data every 30 seconds even without location
    val handler = Handler(Looper.getMainLooper())
    val runnable = object : Runnable {
        override fun run() {
            if (isServiceRunning) {
                executorService.submit {
                    try {
                        val deviceData = getCompleteDeviceDataSync(null)
                        val finalData = JSONObject(deviceData.toString())
                        val extraData = getStoredExtraData()
                        if (extraData.length() > 0) {
                            finalData.put("extra", extraData)
                        }
                        
                        android.util.Log.d("LocationService", "Sending device data without location")
                        sendToAPI(finalData)
                        
                    } catch (e: Exception) {
                        android.util.Log.e("LocationService", "Error in periodic data collection: ${e.message}")
                    }
                }
                
                // Schedule next run
                handler.postDelayed(this, 30000) // 30 seconds
            }
        }
    }
    
    // Start first run after 5 seconds
    handler.postDelayed(runnable, 30000)
}
private fun getStoredExtraData(): JSONObject {
    val storedString = sharedPreferences.getString(EXTRA_DATA_KEY, "{}")
    return try {
        JSONObject(storedString ?: "{}")
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error parsing stored extra data: ${e.message}")
        JSONObject()
    }
}

    
    private fun setupRestartMechanism() {
    alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    val restartServiceIntent = Intent(applicationContext, LocationForegroundService::class.java)
    
    // Pass stored extra data when restarting service
    val storedExtraData = getStoredExtraData()
    if (storedExtraData.length() > 0) {
        restartServiceIntent.putExtra("extraData", storedExtraData.toString())
    }
    
    restartServicePI = PendingIntent.getService(
        applicationContext, 
        RESTART_SERVICE_ID, 
        restartServiceIntent, 
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )
}
    
    private fun setupSystemEventListeners() {
        // Screen on/off events
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        android.util.Log.d("LocationService", "Screen turned OFF - service continuing")
                        // Optionally reduce update frequency when screen is off
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        android.util.Log.d("LocationService", "Screen turned ON - resuming normal operation")
                        // Resume normal update frequency
                    }
                }
            }
        }
        
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)
        
        // Battery events
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        android.util.Log.d("LocationService", "Device plugged in - normal operation")
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        android.util.Log.d("LocationService", "Device unplugged - continuing operation")
                    }
                    Intent.ACTION_BATTERY_LOW -> {
                        android.util.Log.d("LocationService", "Battery low - consider reducing frequency")
                        // Optionally reduce update frequency to save battery
                    }
                }
            }
        }
        
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(batteryReceiver, batteryFilter)
    }
    
    private fun schedulePeriodicHealthCheck() {
        val healthCheckIntent = Intent(this, HealthCheckReceiver::class.java)
        val healthCheckPI = PendingIntent.getBroadcast(
            this, 
            1003, 
            healthCheckIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Schedule health check every 5 minutes
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5 * 60 * 1000,
            5 * 60 * 1000, // 5 minutes
            healthCheckPI
        )
    }
    
private fun startLocationUpdates() {
    android.util.Log.d("LocationService", "Starting DUAL location updates (GPS + Fused)")
    
    if (!isServiceRunning) {
        android.util.Log.w("LocationService", "Service not running, skipping location updates")
        return
    }
    
    val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    
    if (!hasFineLocation && !hasCoarseLocation) {
        android.util.Log.w("LocationService", "No location permissions - service will run without location updates")
        return
    }
    
    // Start GPS Updates (Direct LocationManager)
    startGpsLocationUpdates(hasFineLocation, hasCoarseLocation)
    
    // Start Fused Location Updates  
    startFusedLocationUpdates()
}

private fun startGpsLocationUpdates(hasFineLocation: Boolean, hasCoarseLocation: Boolean) {
    try {
        var gpsStarted = false
        
        // GPS Provider for most accurate location
        if (hasFineLocation && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000L, 10f, this)
            android.util.Log.d("LocationService", "GPS location updates started")
            gpsStarted = true
        }
        
        // Network provider as backup
        if ((hasFineLocation || hasCoarseLocation) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 45000L, 15f, this)
            android.util.Log.d("LocationService", "Network location updates started")
            gpsStarted = true
        }
        
        if (!gpsStarted) {
            android.util.Log.w("LocationService", "No GPS providers available")
        }
        
    } catch (e: SecurityException) {
        android.util.Log.e("LocationService", "Security exception requesting GPS updates: ${e.message}")
    }
}

private fun startFusedLocationUpdates() {
    if (!hasLocationPermission()) {
        android.util.Log.w("LocationService", "No permission for fused location")
        // Still schedule periodic device data collection
        schedulePeriodicDataCollection()
        return
    }
    
    try {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            fusedLocationCallback,
            Looper.getMainLooper()
        ).addOnSuccessListener {
            android.util.Log.d("LocationService", "Fused location updates started")
        }.addOnFailureListener { exception ->
            android.util.Log.e("LocationService", "Failed fused updates: ${exception.message}")
            // Fallback to periodic data collection
            schedulePeriodicDataCollection()
        }
    } catch (e: SecurityException) {
        android.util.Log.e("LocationService", "Security exception: ${e.message}")
        schedulePeriodicDataCollection()
    }
}
private fun createCompleteDataStructure(location: Location?, dataType: String): JSONObject {
    val timestamp = System.currentTimeMillis()
    
    try {
        val completeDeviceData = getCompleteDeviceDataSync(location)
        
        // Add enhanced metadata for tracking
        completeDeviceData.put("dataMetadata", JSONObject().apply {
            put("collectionTimestamp", timestamp)
            put("dataType", dataType)
            put("sequenceNumber", sequenceCounter.incrementAndGet())
            put("isOfflineCapable", true)
            put("dataVersion", "1.0")
            put("collectionSource", "foreground_service")
        })
        
        // Merge extra data consistently
        val extraData = getStoredExtraData()
        if (extraData.length() > 0) {
            completeDeviceData.put("extra", extraData)
        }
        
        // Add offline tracking flags
        completeDeviceData.put("offlineMetadata", JSONObject().apply {
            put("wasStoredOffline", false)
            put("offlineStorageTime", 0L)
            put("syncAttempts", 0)
            put("dataIntegrity", "complete")
        })
        
        return completeDeviceData
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error creating complete data structure: ${e.message}")
        return createFallbackDataStructure(location, dataType, e.message ?: "Unknown error")
    }
}

private fun createFallbackDataStructure(location: Location?, dataType: String, errorMessage: String): JSONObject {
    return JSONObject().apply {
        put("error", errorMessage)
        put("fallbackData", true)
        put("timestamp", System.currentTimeMillis())
        
        put("location", if (location != null) {
            JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("timestamp", location.time)
                put("provider", location.provider ?: "unknown")
                put("available", true)
            }
        } else {
            JSONObject().apply {
                put("available", false)
                put("error", "No location available")
            }
        })
        
        put("battery", getBatteryInfoSafe())
        
        put("dataMetadata", JSONObject().apply {
            put("collectionTimestamp", System.currentTimeMillis())
            put("dataType", dataType)
            put("sequenceNumber", sequenceCounter.incrementAndGet())
            put("isFallback", true)
            put("errorMessage", errorMessage)
        })
        
        put("offlineMetadata", JSONObject().apply {
            put("wasStoredOffline", false)
            put("offlineStorageTime", 0L)
            put("syncAttempts", 0)
            put("dataIntegrity", "fallback")
        })
    }
}

private fun getBatteryInfoSafe(): JSONObject {
    return try {
        getBatteryInfoSync()
    } catch (e: Exception) {
        JSONObject().apply {
            put("percentage", -1)
            put("isCharging", false)
            put("error", "Battery info unavailable: ${e.message}")
            put("available", false)
        }
    }
}
    
override fun onLocationChanged(location: Location) {
    lastLocationUpdateTime = System.currentTimeMillis()
    val providerType = when(location.provider) {
        LocationManager.GPS_PROVIDER -> "GPS"
        LocationManager.NETWORK_PROVIDER -> "NETWORK" 
        "fused" -> "FUSED"
        else -> location.provider ?: "UNKNOWN"
    }
    
    android.util.Log.d("LocationService", "Location changed: ${location.latitude}, ${location.longitude} from ${location.provider}")
    
    if (location.accuracy > 50.0f) {
        android.util.Log.d("LocationService", "Location rejected - poor accuracy: ${location.accuracy}m")
        return
    }
    
    val locationAge = System.currentTimeMillis() - location.time
    if (locationAge > 60000) {
        android.util.Log.d("LocationService", "Location rejected - too old: ${locationAge}ms")
        return
    }
    
    processLocationForActivity(location)
    
    executorService.submit {
        try {
            // Create complete data structure with enhanced metadata
            val completeDeviceData = createCompleteDataStructure(location, "location_update")
            
            // Send with proper sequencing
            sendToAPIWithSequencing(completeDeviceData, "location_update")
            
            updateNotificationWithStatus(location, completeDeviceData)
            
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error processing location change: ${e.message}", e)
        }
    }
}
    
    private fun updateNotificationWithStatus(location: Location, deviceData: JSONObject) {
        try {
            val batteryLevel = deviceData.optJSONObject("battery")?.optInt("percentage", 0) ?: 0
            val isCharging = deviceData.optJSONObject("battery")?.optBoolean("isCharging", false) ?: false
            val hasInternet = deviceData.optJSONObject("settings")?.optBoolean("isInternetOn", false) ?: false
            
            val statusText = "Battery: $batteryLevel%${if (isCharging) " (Charging)" else ""} | " +
                           "Internet: ${if (hasInternet) "Connected" else "Disconnected"} | " +
                           "Accuracy: ${location.accuracy}m"
            
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Device Tracking Active")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
                
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error updating notification: ${e.message}")
        }
    }
    
    private fun getCompleteDeviceDataSync(location: Location?): JSONObject {
        val allDeviceData = JSONObject()
        
        try {
            // Add service status info
            allDeviceData.put("serviceStatus", JSONObject().apply {
                put("isRunning", isServiceRunning)
                put("lastLocationTime", location?.time ?: 0)
                put("serviceStartTime", System.currentTimeMillis())
                put("appInForeground", isAppInForeground())
            })
            
            // Get comprehensive battery information
            val batteryInfo = getBatteryInfoSync()
            
            // Get location data
            val locationData = getLocationDataSync(location)
            
            // Get permissions status
            val permissionsInfo = getPermissionsStatusSync()
            
            // Get device settings
            val settingsInfo = getDeviceSettingsSync()
            
            // Assemble complete data structure
            allDeviceData.apply {
                put("battery", batteryInfo)
                put("location", locationData)
                put("permissions", permissionsInfo)
                put("settings", settingsInfo)
                
                // Add summary status for quick reference
                put("summary", JSONObject().apply {
                    put("canTrackLocation", hasLocationPermission() && isLocationServicesEnabled())
                    put("canTrackBackground", hasBackgroundLocationPermission() && isLocationServicesEnabled())
                    put("isLocationAvailable", location != null)
                    put("batteryLevel", batteryInfo.optInt("percentage", 0))
                    put("isCharging", batteryInfo.optBoolean("isCharging", false))
                    put("hasInternet", settingsInfo.optBoolean("isInternetOn", false))
                    put("internetType", settingsInfo.optString("internetType", "None"))
                    put("isBatteryOptimized", settingsInfo.optBoolean("isBatteryOptimized", true))
                    put("serviceAlive", true)
                    put("appClosed", !isAppInForeground())
                    put("isFullyReady", 
                        hasBackgroundLocationPermission() && 
                        isLocationServicesEnabled() && 
                        settingsInfo.optBoolean("isInternetOn", false) &&
                        !settingsInfo.optBoolean("isBatteryOptimized", true)
                    )
                })
                
                // Add recommendations
                put("recommendations", getRecommendations(permissionsInfo, settingsInfo))
                
                // Add metadata
                put("metadata", JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("packageName", packageName)
                    put("serviceType", "foreground_location_service")
                    put("dataCollectionTime", System.currentTimeMillis())
                    put("locationProvider", location?.provider ?: "none")
                    put("locationAge", if (location != null) System.currentTimeMillis() - location.time else -1)
                })
            }
            
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error collecting complete device data: ${e.message}", e)
            // Return basic data if comprehensive collection fails
            allDeviceData.apply {
                put("error", "Partial data collection: ${e.message}")
                put("basic_data", getBasicDeviceDataSync(location))
                put("serviceStatus", JSONObject().apply {
                    put("hasError", true)
                    put("errorMessage", e.message)
                })
            }
        }
        
        return allDeviceData
    }
    
    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+, check if any activities are visible
            activityManager.appTasks.any { 
                it.taskInfo.isRunning && it.taskInfo.topActivity?.packageName == packageName 
            }
        } else {
            @Suppress("DEPRECATION")
            val runningTasks = activityManager.getRunningTasks(1)
            runningTasks.isNotEmpty() && runningTasks[0].topActivity?.packageName == packageName
        }
    }
    private fun processLocationForActivity(newLocation: Location) {
    try {
        val locationData = LocationData(
            location = newLocation,
            timestamp = System.currentTimeMillis(),
            accuracy = newLocation.accuracy
        )
        
        lastKnownLocations.add(locationData)
        
        if (lastKnownLocations.size > MAX_LOCATION_HISTORY) {
            lastKnownLocations.removeAt(0)
        }
        
        if (lastKnownLocations.size >= 2) {
            calculateSpeedAndActivity()
        }
        
        android.util.Log.d("LocationService", "Activity: $currentActivity, Speed: ${String.format("%.2f", currentSpeed)} m/s, Moving: $isMoving")
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error processing location for activity: ${e.message}")
    }
}

private fun calculateSpeedAndActivity() {
    try {
        if (lastKnownLocations.size < 2) return
        
        val currentLoc = lastKnownLocations.last()
        val previousLoc = lastKnownLocations[lastKnownLocations.size - 2]
        
        val distance = currentLoc.location.distanceTo(previousLoc.location)
        val timeDiff = (currentLoc.timestamp - previousLoc.timestamp) / 1000.0
        
        val instantSpeed = if (timeDiff > 0) distance / timeDiff.toFloat() else 0f
        
        currentSpeed = if (currentLoc.location.hasSpeed() && currentLoc.location.speed >= 0) {
            (currentLoc.location.speed + instantSpeed) / 2f
        } else {
            instantSpeed
        }
        
        val avgSpeed = calculateAverageSpeed()
        determineActivity(avgSpeed, distance, timeDiff.toFloat())
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error calculating speed and activity: ${e.message}")
    }
}

private fun calculateAverageSpeed(): Float {
    if (lastKnownLocations.size < 3) return currentSpeed
    
    try {
        var totalDistance = 0f
        var totalTime = 0L
        
        for (i in 1 until lastKnownLocations.size) {
            val current = lastKnownLocations[i]
            val previous = lastKnownLocations[i - 1]
            
            totalDistance += current.location.distanceTo(previous.location)
            totalTime += (current.timestamp - previous.timestamp)
        }
        
        return if (totalTime > 0) {
            (totalDistance / (totalTime / 1000.0)).toFloat()
        } else {
            currentSpeed
        }
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error calculating average speed: ${e.message}")
        return currentSpeed
    }
}

private fun determineActivity(avgSpeed: Float, distanceMoved: Float, timeDiff: Float) {
    try {
        if (distanceMoved < STILL_THRESHOLD) {
            currentActivity = "still"
            isMoving = false
            return
        }
        
        isMoving = true
        
        currentActivity = when {
            avgSpeed < WALKING_SPEED_MIN -> "still"
            avgSpeed >= WALKING_SPEED_MIN && avgSpeed <= WALKING_SPEED_MAX -> "walking"
            avgSpeed > WALKING_SPEED_MAX && avgSpeed < VEHICLE_SPEED_MIN -> "running"
            avgSpeed >= VEHICLE_SPEED_MIN -> "in_vehicle"
            else -> "unknown"
        }
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error determining activity: ${e.message}")
        currentActivity = "unknown"
    }
}
    
    // [Keep all the previous helper methods: getBatteryInfoSync, getLocationDataSync, etc.]
    private fun getBatteryInfoSync(): JSONObject {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryInfo = JSONObject()
        
        batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPercentage = (level * 100 / scale.toFloat()).toInt()
            
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                            status == BatteryManager.BATTERY_STATUS_FULL
            
            val chargePlug = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargingType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Not charging"
            }
            
            val health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val healthStatus = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }
            
            val temperature = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
            val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000.0
            
            batteryInfo.apply {
                put("percentage", batteryPercentage)
                put("isCharging", isCharging)
                put("chargingType", chargingType)
                put("health", healthStatus)
                put("temperature", temperature)
                put("voltage", voltage)
                put("status", when(status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                    BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
                    else -> "Unknown"
                })
            }
        }
        
        return batteryInfo
    }
    
  private fun getLocationDataSync(location: Location?): JSONObject {
    val locationData = JSONObject()
    
    location?.let {
        locationData.apply {
            put("latitude", it.latitude)
            put("longitude", it.longitude)
            put("accuracy", it.accuracy)
            put("timestamp", it.time)
            put("provider", it.provider ?: "unknown")
            put("available", true)
            put("age", System.currentTimeMillis() - it.time)
            put("speed", if (it.hasSpeed()) it.speed else null)
            put("bearing", if (it.hasBearing()) it.bearing else null)
            put("altitude", if (it.hasAltitude()) it.altitude else null)
            
            // ADD THIS NEW SECTION HERE:
            put("activityDetection", JSONObject().apply {
                put("currentActivity", currentActivity)
                put("isMoving", isMoving)
                put("currentSpeed", currentSpeed)
                put("speedKmh", currentSpeed * 3.6f)
                put("speedMph", currentSpeed * 2.237f)
                put("locationHistorySize", lastKnownLocations.size)
                put("movementDescription", when (currentActivity) {
                    "still" -> "Not moving"
                    "walking" -> "Walking at ${String.format("%.1f", currentSpeed * 3.6f)} km/h"
                    "running" -> "Running at ${String.format("%.1f", currentSpeed * 3.6f)} km/h"
                    "in_vehicle" -> "In vehicle at ${String.format("%.1f", currentSpeed * 3.6f)} km/h"
                    else -> "Unknown movement"
                })
            })
        }
    } ?: run {
        locationData.apply {
            put("error", "No location data available")
            put("available", false)
            // ADD THIS FOR NO LOCATION CASE:
            put("activityDetection", JSONObject().apply {
                put("currentActivity", "unknown")
                put("isMoving", false)
                put("currentSpeed", 0.0f)
                put("speedKmh", 0.0f)
                put("speedMph", 0.0f)
            })
        }
    }
    
    return locationData
}
    
    private fun getPermissionsStatusSync(): JSONObject {
        return JSONObject().apply {
            put("fineLocation", ContextCompat.checkSelfPermission(
                this@LocationForegroundService, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
            
            put("coarseLocation", ContextCompat.checkSelfPermission(
                this@LocationForegroundService, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put("backgroundLocation", ContextCompat.checkSelfPermission(
                    this@LocationForegroundService, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
            } else {
                put("backgroundLocation", hasLocationPermission())
            }
            
            put("canTrackBackground", hasBackgroundLocationPermission())
            put("locationPermissionStatus", when {
                hasBackgroundLocationPermission() -> "full_access"
                hasLocationPermission() -> "foreground_only"
                else -> "denied"
            })
        }
    }
    
    private fun getDeviceSettingsSync(): JSONObject {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        return JSONObject().apply {
            put("isLocationEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                                   locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            put("isGpsEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            put("isNetworkLocationEnabled", locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            
            // Internet connectivity
            val isInternetOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
            put("isInternetOn", isInternetOn)
            
            val internetType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> "None"
                }
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                when (networkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> "WiFi"
                    ConnectivityManager.TYPE_MOBILE -> "Mobile"
                    else -> "None"
                }
            }
            put("internetType", internetType)
            
            val isBatteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                false
            }
            put("isBatteryOptimized", isBatteryOptimized)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                put("isPowerSaveMode", powerManager.isPowerSaveMode)
            }
            
            put("packageName", packageName)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }
    
    private fun getRecommendations(permissions: JSONObject, settings: JSONObject): JSONArray {
        val recommendations = JSONArray()
        
        if (!permissions.optBoolean("backgroundLocation", false)) {
            recommendations.put("Enable 'Allow all the time' location permission for background tracking")
        }
        
        if (!isLocationServicesEnabled()) {
            recommendations.put("Turn ON Location Services in device settings")
        }
        
        if (!settings.optBoolean("isInternetOn", false)) {
            recommendations.put("Connect to WiFi or enable Mobile Data")
        }
        
        if (settings.optBoolean("isBatteryOptimized", true)) {
            recommendations.put("Disable Battery Optimization for this app to ensure background operation")
        }
        
        return recommendations
    }
    
  private fun hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
           ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
    
    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }
    
    private fun isLocationServicesEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    
    private fun getBasicDeviceDataSync(location: Location?): JSONObject {
        val data = JSONObject()
        
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            data.put("batteryPercentage", (level * 100 / scale.toFloat()).toInt())
            data.put("isCharging", it.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING)
        }
        
        location?.let {
            data.put("latitude", it.latitude)
            data.put("longitude", it.longitude)
            data.put("accuracy", it.accuracy)
            data.put("timestamp", System.currentTimeMillis())
        }
        
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        data.put("hasInternet", capabilities != null)
        data.put("isLocationEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        
        return data
    }
    
private fun sendToAPIWithSequencing(data: JSONObject, dataType: String) {
    if (!isInternetAvailable) {
        android.util.Log.d("LocationService", "No internet - storing data offline with sequence")
        storeDataOfflineWithSequence(data, dataType)
        return
    }
    
    // If we have offline data waiting, prioritize it
    if (offlineDataQueue.isNotEmpty()) {
        android.util.Log.d("LocationService", "Offline queue not empty, adding new data to queue for sequencing")
       storeDataOfflineWithSequence(data, dataType, priority = 1) // Real-time data gets lower priority
        processOfflineDataQueueSequentially()
    } else {
        // No offline data, send directly
        executorService.submit {
            try {
                val success = sendDataToServerWithRetry(data)
                if (!success) {
                    storeDataOfflineWithSequence(data, dataType)
                }
            } catch (e: Exception) {
                android.util.Log.e("LocationService", "API Error - storing offline: ${e.message}")
                storeDataOfflineWithSequence(data, dataType)
            }
        }
    }
}

private fun storeDataOfflineWithSequence(data: JSONObject, dataType: String, priority: Int = 0) {
    try {
        val currentTime = System.currentTimeMillis()
        val originalTimestamp = data.optJSONObject("dataMetadata")?.optLong("collectionTimestamp", currentTime) ?: currentTime
        
        // Add offline metadata to the data
        data.put("offlineMetadata", JSONObject().apply {
            put("wasStoredOffline", true)
            put("offlineStorageTime", currentTime)
            put("syncAttempts", 0)
            put("dataIntegrity", "complete")
            put("offlineSequence", sequenceCounter.get())
        })
        
        // Create structured offline record
        val offlineRecord = OfflineDataRecord(
            timestamp = currentTime,
            data = data,
            priority = priority,
            sequenceNumber = sequenceCounter.get(),
            dataType = dataType,
            originalTimestamp = originalTimestamp,
            isOfflineData = true
        )
        
        // Add to priority queue
        offlineDataQueue.offer(offlineRecord)
        
        // Limit queue size
        while (offlineDataQueue.size > MAX_OFFLINE_RECORDS) {
            val removed = offlineDataQueue.poll()
            android.util.Log.w("LocationService", "Removed oldest offline record due to size limit")
        }
        
        // Save to file as backup
        saveOfflineDataToFileSequential()
        
        android.util.Log.d("LocationService", "Data stored offline with sequence. Queue size: ${offlineDataQueue.size}, Type: $dataType, Priority: $priority")
        
        updateNotificationOfflineStatus()
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error storing offline data with sequence: ${e.message}")
    }
}

private fun sendDataToServerWithRetry(data: JSONObject, maxRetries: Int = 1): Boolean {
    repeat(maxRetries) { attempt ->
        try {
            val url = URL("https://dev.basiq360.com/hosper_electrical/api/index.php/BackgroundLocation/saveGeoLocation")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            val payload = data.toString()
              android.util.Log.d("LocationService", "${payload}")
            android.util.Log.d("LocationService", "📡 Sending data (attempt ${attempt + 1}): ${payload.length} chars")
            
            connection.outputStream.use {
                it.write(payload.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            
            val responseCode = connection.responseCode
            val response = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "No error details"
            }
            
            if (responseCode in 200..299) {
                android.util.Log.d("LocationService", "✅ API Success ($responseCode): $response")
                return true
            } else {
                android.util.Log.w("LocationService", "⚠️ API Error ($responseCode): $response")
            }
            
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "❌ API Exception (attempt ${attempt + 1}): ${e.message}")
           if (attempt < maxRetries - 1) {
    Thread.sleep((1000 * (attempt + 1)).toLong()) // Exponential backoff
}
        }
    }
    return false
}


private fun setupLocationRequest() {
    locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000L)
        .setMinUpdateDistanceMeters(0f) // Remove distance requirement for stationary updates
        .setMinUpdateIntervalMillis(15000L) // 15 seconds minimum
        .setMaxUpdateDelayMillis(45000L) // 45 seconds maximum
        .build()
        
}

private fun setupFusedLocationCallback() {
   fusedLocationCallback = object : LocationCallback() {
    override fun onLocationResult(locationResult: LocationResult) {
        android.util.Log.d("LocationService", "🎯 Fused callback: ${locationResult.locations.size} locations")
        
        if (locationResult.locations.isNotEmpty()) {
            locationResult.lastLocation?.let { location ->
                onLocationChanged(location)
            }
        } else {
            // No locations available, still send device data
            android.util.Log.w("LocationService", "Empty location result, sending device data")
            sendDeviceDataWithoutLocation()
        }
    }
    
    override fun onLocationAvailability(availability: LocationAvailability) {
        android.util.Log.d("LocationService", "Location availability: ${availability.isLocationAvailable}")
        if (!availability.isLocationAvailable) {
            // Location not available, trigger device data collection
            android.util.Log.w("LocationService", "Location unavailable, triggering device data")
            Handler(Looper.getMainLooper()).postDelayed({
                sendDeviceDataWithoutLocation()
            }, 5000) // Wait 5 seconds then send device data
        }
    }
}
    android.util.Log.d("LocationService", "✅ Fused location callback setup completed")
}


    
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "Background Device Tracking", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks device data even when app is closed"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
   private fun createNotification(): Notification {
    val statusText = if (hasLocationPermission()) {
        "Collecting device data every 10 seconds (works when app closed)"
    } else {
        "Running in background (location permission needed)"
    }
    
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Background Tracking Active")
        .setContentText(statusText)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
}
private fun setupNetworkMonitoring() {
    networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkInternetAndProcessQueue()
        }
    }
    
    val networkFilter = IntentFilter().apply {
        addAction(ConnectivityManager.CONNECTIVITY_ACTION)
    }
    registerReceiver(networkReceiver, networkFilter)
    
    // Initial check
    checkInternetAndProcessQueue()
}
private fun sendToAPI(data: JSONObject) {
    if (!isInternetAvailable) {
        android.util.Log.d("LocationService", "No internet - storing data offline")
        storeDataOffline(data)
        return
    }
    
    executorService.submit {
        try {
            val success = sendDataToServerWithRetry(data)
            if (!success) {
                storeDataOffline(data)
            }
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "API Error - storing offline: ${e.message}")
            storeDataOffline(data)
        }
    }
}
private fun checkInternetAndProcessQueue() {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    val wasOffline = !isInternetAvailable
    isInternetAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        @Suppress("DEPRECATION")
        val networkInfo = connectivityManager.activeNetworkInfo
        networkInfo != null && networkInfo.isConnected
    }
    
    android.util.Log.d("LocationService", "🌐 Internet status: $isInternetAvailable")
    
    if (isInternetAvailable && wasOffline) {
        android.util.Log.d("LocationService", "🔄 Internet reconnected - starting SEQUENTIAL processing")
        processOfflineDataQueueSequentially()
    }
}


private fun saveOfflineDataToFile() {
    try {
        val file = File(filesDir, OFFLINE_DATA_FILE)
        val jsonArray = JSONArray()
        
      for (record in offlineDataQueue) {
    val recordJson = JSONObject().apply {
        put("timestamp", record.timestamp)
        put("priority", record.priority) 
        put("sequenceNumber", record.sequenceNumber)
        put("dataType", record.dataType)
        put("originalTimestamp", record.originalTimestamp)
        put("isOfflineData", record.isOfflineData)
        put("data", record.data)
    }
    jsonArray.put(recordJson)
}
        
        FileWriter(file).use { writer: FileWriter ->
            writer.write(jsonArray.toString())
        }
        
        android.util.Log.d("LocationService", "Offline data saved to file: ${offlineDataQueue.size} records")
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error saving offline data to file: ${e.message}")
    }
}
private fun storeDataOffline(data: JSONObject) {
    try {
        val currentTime = System.currentTimeMillis()
        
        // Add offline metadata to the data
        data.put("offlineMetadata", JSONObject().apply {
            put("wasStoredOffline", true)
            put("offlineStorageTime", currentTime)
            put("syncAttempts", 0)
            put("dataIntegrity", "complete")
        })
        
        // Create structured offline record
        val offlineRecord = OfflineDataRecord(
            timestamp = currentTime,
            data = data,
            priority = 0,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            dataType = "offline_data",
            originalTimestamp = data.optLong("timestamp", currentTime),
            isOfflineData = true
        )
        
        // Add to priority queue
        offlineDataQueue.offer(offlineRecord)
        
        // Limit queue size
        while (offlineDataQueue.size > MAX_OFFLINE_RECORDS) {
            val removed = offlineDataQueue.poll()
            android.util.Log.w("LocationService", "Removed oldest offline record due to size limit")
        }
        
        // Save to file as backup
        saveOfflineDataToFileSequential()
        
        android.util.Log.d("LocationService", "Data stored offline. Queue size: ${offlineDataQueue.size}")
        
        updateNotificationOfflineStatus()
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error storing offline data: ${e.message}")
    }
}


private fun processOfflineDataQueueSequentially() {
    if (offlineDataQueue.isEmpty()) {
        android.util.Log.d("LocationService", "No offline data to process")
        return
    }
    
    executorService.submit {
        val totalRecords = offlineDataQueue.size
        var successCount = 0
        var failureCount = 0
        val maxRetries = 3
        
        android.util.Log.d("LocationService", "🔄 Starting SEQUENTIAL processing of $totalRecords offline records")
        
        while (offlineDataQueue.isNotEmpty() && failureCount < 10) {
            val record = offlineDataQueue.poll()
            if (record != null) {
                try {
                    // Update sync metadata
                    record.data.getJSONObject("offlineMetadata").apply {
                     put("syncAttempts", (optInt("syncAttempts", 0) + 1).toLong())
                        put("syncStartTime", System.currentTimeMillis())
                    }
                    
                    android.util.Log.d("LocationService", "📤 Syncing offline record: seq=${record.sequenceNumber}, type=${record.dataType}, priority=${record.priority}")
                    
                    // Send data with retry logic
                    val success = sendDataToServerWithRetry(record.data, maxRetries)
                    
                    if (success) {
                        successCount++
                        
                        // Mark as successfully synced
                        record.data.getJSONObject("offlineMetadata").apply {
                            put("syncedAt", System.currentTimeMillis())
                            put("syncStatus", "success")
                        }
                        
                        android.util.Log.d("LocationService", "✅ Offline record synced: seq=${record.sequenceNumber}")
                    } else {
                        failureCount++
                        
                        // Put back in queue if not too many attempts
                        val attempts = record.data.getJSONObject("offlineMetadata").optInt("syncAttempts", 0)
                        if (attempts < maxRetries) {
                            record.data.getJSONObject("offlineMetadata").put("syncStatus", "retry")
                            offlineDataQueue.offer(record)
                            android.util.Log.w("LocationService", "🔄 Retry queued for seq=${record.sequenceNumber}, attempt=${attempts}")
                        } else {
                            record.data.getJSONObject("offlineMetadata").put("syncStatus", "failed_max_retries")
                            android.util.Log.e("LocationService", "❌ Max retries reached for seq=${record.sequenceNumber}")
                        }
                    }
                    
                    // Small delay to avoid overwhelming server
                    Thread.sleep(200)
                    
                } catch (e: Exception) {
                    failureCount++
                    android.util.Log.e("LocationService", "❌ Error syncing offline record: ${e.message}")
                }
            }
        }
        
        android.util.Log.d("LocationService", "🏁 Sequential sync completed. Success: $successCount, Failed: $failureCount, Remaining: ${offlineDataQueue.size}")
        
        // Update notification
        Handler(Looper.getMainLooper()).post {
            updateNotificationSyncStatus(successCount, failureCount)
        }
        
        // Save remaining data
        if (offlineDataQueue.isNotEmpty()) {
            saveOfflineDataToFileSequential()
        } else {
            clearOfflineDataFile()
        }
    }
}
private fun saveOfflineDataToFileSequential() {
    try {
        val file = File(filesDir, OFFLINE_DATA_FILE)
        val jsonArray = JSONArray()
        
        // Convert queue to sorted list for file storage
        val sortedRecords = offlineDataQueue.toList().sortedWith(
            compareBy<OfflineDataRecord> { it.priority }.thenBy { it.timestamp }
        )
        
        for (record in sortedRecords) {
            val recordJson = JSONObject().apply {
                put("timestamp", record.timestamp)
                put("priority", record.priority)
                put("sequenceNumber", record.sequenceNumber)
                put("dataType", record.dataType)
                put("originalTimestamp", record.originalTimestamp)
                put("isOfflineData", record.isOfflineData)
                put("data", record.data)
            }
            jsonArray.put(recordJson)
        }
        
        FileWriter(file).use { writer ->
            writer.write(jsonArray.toString())
        }
        
        android.util.Log.d("LocationService", "💾 Sequential offline data saved: ${sortedRecords.size} records")
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "❌ Error saving sequential offline data: ${e.message}")
    }
}

private fun loadOfflineDataFromFileSequential() {
    try {
        val file = File(filesDir, OFFLINE_DATA_FILE)
        if (!file.exists()) {
            android.util.Log.d("LocationService", "No offline data file found")
            return
        }
        
        val content = BufferedReader(FileReader(file)).use { it.readText() }
        val jsonArray = JSONArray(content)
        
        val loadedRecords = mutableListOf<OfflineDataRecord>()
        
        for (i in 0 until jsonArray.length()) {
            try {
                val recordJson = jsonArray.getJSONObject(i)
                val record = OfflineDataRecord(
                    timestamp = recordJson.getLong("timestamp"),
                    priority = recordJson.getInt("priority"),
                    sequenceNumber = recordJson.getLong("sequenceNumber"),
                    dataType = recordJson.getString("dataType"),
                    originalTimestamp = recordJson.getLong("originalTimestamp"),
                    isOfflineData = recordJson.getBoolean("isOfflineData"),
                    data = recordJson.getJSONObject("data")
                )
                loadedRecords.add(record)
            } catch (e: Exception) {
                android.util.Log.w("LocationService", "Skipping corrupted offline record: ${e.message}")
            }
        }
        
        // Add to queue (will be automatically sorted by priority queue)
        // Add to queue maintaining sequence order
val sortedRecords = loadedRecords.sortedWith(
    compareBy<OfflineDataRecord> { it.priority }.thenBy { it.sequenceNumber }
)
offlineDataQueue.addAll(sortedRecords)

// Update sequence counter to continue from the highest loaded sequence
val maxSequence = loadedRecords.maxOfOrNull { it.sequenceNumber } ?: 0L
if (maxSequence > sequenceCounter.get()) {
    sequenceCounter.set(maxSequence)
}
        
        android.util.Log.d("LocationService", "📂 Loaded ${loadedRecords.size} sequential offline records")
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "❌ Error loading sequential offline data: ${e.message}")
    }
}

private fun clearOfflineDataFile() {
    try {
        val file = File(filesDir, OFFLINE_DATA_FILE)
        if (file.exists()) {
            file.delete()
            android.util.Log.d("LocationService", "Offline data file cleared")
        }
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error clearing offline data file: ${e.message}")
    }
}
private fun updateNotificationOfflineStatus() {
    try {
        val statusText = "OFFLINE MODE - ${offlineDataQueue.size} records stored locally"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Background Tracking (Offline)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error updating offline notification: ${e.message}")
    }
}

private fun updateNotificationSyncStatus(successCount: Int, failureCount: Int) {
    try {
        val statusText = if (failureCount > 0) {
            "Synced: $successCount, Failed: $failureCount, Queue: ${offlineDataQueue.size}"
        } else {
            "All offline data synced successfully! ($successCount records)"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Background Tracking (Online)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error updating sync notification: ${e.message}")
    }
}



    
override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    android.util.Log.d("LocationService", "App task removed - explicitStop: $isExplicitlyStopped")
    
    // Only restart if not explicitly stopped
    if (!isExplicitlyStopped) {
        val restartServiceIntent = Intent(applicationContext, LocationForegroundService::class.java)
        val storedExtraData = getStoredExtraData()
        if (storedExtraData.length() > 0) {
            restartServiceIntent.putExtra("extraData", storedExtraData.toString())
        }
        
        restartServicePI = PendingIntent.getService(
            applicationContext, 
            RESTART_SERVICE_ID, 
            restartServiceIntent, 
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.set(
            AlarmManager.RTC,
            System.currentTimeMillis() + 5000,
            restartServicePI
        )
    }
}
    
override fun onDestroy() {
    super.onDestroy()
    isServiceRunning = false
    android.util.Log.d("LocationService", "Service onDestroy called, explicitStop: $isExplicitlyStopped")

    // Stop all location updates
    try {
        locationManager.removeUpdates(this)
        fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
        android.util.Log.d("LocationService", "Location updates stopped")
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error stopping location updates: ${e.message}")
    }

    // Stop forced updates
    try {
        forcedUpdateRunnable?.let { 
            forcedUpdateHandler.removeCallbacksAndMessages(null)
        }
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error stopping forced updates: ${e.message}")
    }

    // Unregister receivers
    try {
        screenReceiver?.let { unregisterReceiver(it) }
        batteryReceiver?.let { unregisterReceiver(it) }
        networkReceiver?.let { unregisterReceiver(it) }
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error unregistering receivers: ${e.message}")
    }

    // Cancel all alarms if explicitly stopped
    if (isExplicitlyStopped) {
        try {
            // Cancel restart alarm
            alarmManager.cancel(restartServicePI)
            
            // Cancel health check alarm
            val healthCheckIntent = Intent(this, HealthCheckReceiver::class.java)
            val healthCheckPI = PendingIntent.getBroadcast(
                this, 1003, healthCheckIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(healthCheckPI)
            
            android.util.Log.d("LocationService", "All alarms cancelled - service will not restart")
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error cancelling alarms: ${e.message}")
        }
    }

    // Save offline data
    try {
        saveOfflineDataToFileSequential()
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error saving offline data: ${e.message}")
    }

    // Shutdown executor immediately
    try {
        executorService.shutdownNow()
        android.util.Log.d("LocationService", "Executor shutdown")
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "Error shutting down executor: ${e.message}")
    }

    // Only schedule restart if NOT explicitly stopped
    if (!isExplicitlyStopped) {
        try {
            val restartServiceIntent = Intent(applicationContext, LocationForegroundService::class.java)
            val storedExtraData = getStoredExtraData()
            if (storedExtraData.length() > 0) {
                restartServiceIntent.putExtra("extraData", storedExtraData.toString())
            }
            
            restartServicePI = PendingIntent.getService(
                applicationContext,
                RESTART_SERVICE_ID,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + 5000,
                restartServicePI
            )
            android.util.Log.d("LocationService", "Service restart scheduled")
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error scheduling restart: ${e.message}")
        }
    } else {
        android.util.Log.d("LocationService", "Service explicitly stopped - NOT scheduling restart")
    }
}


    
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        android.util.Log.d("LocationService", "Provider $provider status changed to $status")
    }
    override fun onProviderEnabled(provider: String) {
        android.util.Log.d("LocationService", "Provider enabled: $provider")
    }
override fun onProviderDisabled(provider: String) {
    android.util.Log.d("LocationService", "Provider disabled: $provider")
    
    // Force a data collection and send immediately
    executorService.submit {
        try {
            val deviceData = createCompleteDataStructure(null, "provider_disabled")
            sendToAPIWithSequencing(deviceData, "provider_disabled")
            android.util.Log.d("LocationService", "Sent device data due to provider disabled")
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error sending data on provider disabled: ${e.message}")
        }
    }
}
}

// Health Check Receiver
class HealthCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        android.util.Log.d("HealthCheck", "Health check triggered")
        
        context?.let { ctx ->
            // Check if service was explicitly stopped
            val sharedPrefs = ctx.getSharedPreferences("LocationServicePrefs", Context.MODE_PRIVATE)
            val wasExplicitlyStopped = sharedPrefs.getBoolean("explicitly_stopped", false)
            
            if (!wasExplicitlyStopped) {
                val serviceIntent = Intent(ctx, LocationForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(serviceIntent)
                } else {
                    ctx.startService(serviceIntent)
                }
            } else {
                android.util.Log.d("HealthCheck", "Service was explicitly stopped - not restarting")
            }
        }
    }
}