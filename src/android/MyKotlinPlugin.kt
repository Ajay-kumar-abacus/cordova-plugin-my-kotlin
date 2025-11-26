package com.example.mykotlinplugin

import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CallbackContext
import org.json.JSONArray
import org.json.JSONObject
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.content.Context
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.os.PowerManager
import android.content.pm.ApplicationInfo
import android.os.Handler  
import android.os.Looper  
import android.net.Uri

class MyKotlinPlugin : CordovaPlugin() {

       private var isTrackingActive = false
    private var pendingStartCallback: CallbackContext? = null
    private var permissionRetryCount = 0
    private val MAX_PERMISSION_RETRIES = 3
    
    private var locationCallbackContext: CallbackContext? = null
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val BACKGROUND_LOCATION_PERMISSION_REQUEST = 1002
    
override fun execute(action: String?, args: JSONArray?, callbackContext: CallbackContext?): Boolean {
    return when (action) {
        "startTracking" -> {
            pendingStartCallback = callbackContext
            val extraData = args?.optJSONObject(0) ?: JSONObject()
            startTrackingWithPermissions(extraData)
            true
        }
         "getDeviceDataNoPermissionRequest" -> {
            locationCallbackContext = callbackContext
            getDeviceDataNoPermissionRequest()
            true
        }
        "stopTracking" -> {
            stopBackgroundTracking(callbackContext)
            true
        }
        
        // ADD THESE MISSING CASES - THIS IS THE MAIN FIX:
        "openLocationPermissions" -> {
            openLocationPermissions(callbackContext)
            true
        }
        "openBatteryOptimizationSettings" -> {
            openBatteryOptimizationSettings(callbackContext)
            true
        }
        "openLocationServicesSettings" -> {
            openLocationServicesSettings(callbackContext)
            true
        }
        "openPermissionSettings" -> {
            openPermissionSettings(callbackContext)
            true
        }
        
        "checkAndRequestPermissions" -> {
            locationCallbackContext = callbackContext
            checkAndRequestAllPermissions()
            true
        }
        "getTrackingStatus" -> {
            getTrackingStatus(callbackContext)
            true
        }
        "coolMethod" -> {
            val name = args?.optString(0, "World") ?: "World"
            callbackContext?.success("Hello, $name from Kotlin!")
            true
        }
        "startBackgroundTracking" -> {
            locationCallbackContext = callbackContext
            val extraData = args?.optJSONObject(0) ?: JSONObject()
            startBackgroundLocationService(extraData)
            callbackContext?.success("Background tracking started")
            true
        }
        "getCurrentLocation" -> {
            locationCallbackContext = callbackContext
            getCurrentLocation()
            true
        }
        "getBatteryInfo" -> {
            getBatteryInfo(callbackContext)
            true
        }
        "getBatteryPercentage" -> {
            getBatteryPercentage(callbackContext)
            true
        }
        "getBatteryChargingStatus" -> {
            getBatteryChargingStatus(callbackContext)
            true
        }
        "getDeviceInfo" -> {
            locationCallbackContext = callbackContext
            getDeviceInfo()
            true
        }
        "requestBackgroundLocation" -> {
            locationCallbackContext = callbackContext
            requestBackgroundLocationPermission()
            true
        }
        "getPermissionsStatus" -> {
            getPermissionsStatus(callbackContext)
            true
        }
        "getDeviceSettings" -> {
            getDeviceSettings(callbackContext)
            true
        }
        "getCompleteDeviceInfo" -> {
            locationCallbackContext = callbackContext
            getCompleteDeviceInfo()
            true
        }
        "getAllDeviceData" -> {
            locationCallbackContext = callbackContext
            getAllDeviceData()
            true
        }
        else -> false
    }
}
 private fun startBackgroundLocationService(extraData: JSONObject = JSONObject()) { // ADD PARAMETER
    val intent = Intent(cordova.activity, LocationForegroundService::class.java)
    intent.putExtra("extraData", extraData.toString()) // ADD THIS LINE
    cordova.activity.startForegroundService(intent)
}
    private fun getCurrentLocation() {
        // Check if location permissions are granted
        if (hasLocationPermission()) {
            getLocation()
        } else {
            requestLocationPermission()
        }
    }

    // NEW FUNCTION: Get device data WITHOUT requesting permissions
private fun getDeviceDataNoPermissionRequest() {
    try {
        android.util.Log.d("MyKotlinPlugin", "getDeviceDataNoPermissionRequest called")
        
        // 1. Get battery info (no permissions needed)
        val batteryInfo = getBatteryInfoSync()
        
        // 2. Get current permissions status (just check, don't request)
        val permissionsInfo = getPermissionsStatusSync()
        
        // 3. Get device settings
        val settingsInfo = getDeviceSettingsSync()
        
        // 4. Try to get location ONLY if permission already exists
        val locationData = if (hasLocationPermission()) {
            try {
                getLocationDataSyncSafe()
            } catch (e: Exception) {
                android.util.Log.w("MyKotlinPlugin", "Could not get location: ${e.message}")
                createNoLocationData("Permission exists but location unavailable")
            }
        } else {
            createNoLocationData("Location permission not granted")
        }
        
        // 5. Create complete response
        val allDeviceData = JSONObject().apply {
            put("battery", batteryInfo)
            put("location", locationData)
            put("permissions", permissionsInfo)
            put("settings", settingsInfo)
            
            // Add summary status
            put("summary", JSONObject().apply {
                put("canTrackLocation", hasLocationPermission() && settingsInfo.optBoolean("isLocationEnabled", false))
                put("canTrackBackground", hasBackgroundLocationPermission() && settingsInfo.optBoolean("isLocationEnabled", false))
                put("isLocationAvailable", locationData.optBoolean("available", false))
                put("batteryLevel", batteryInfo.optInt("percentage", 0))
                put("isCharging", batteryInfo.optBoolean("isCharging", false))
                put("hasInternet", settingsInfo.optBoolean("isInternetOn", false))
                put("internetType", settingsInfo.optString("internetType", "None"))
                put("isBatteryOptimized", settingsInfo.optBoolean("isBatteryOptimized", true))
                put("isFullyReady", 
                    hasBackgroundLocationPermission() && 
                    settingsInfo.optBoolean("isLocationEnabled", false) && 
                    settingsInfo.optBoolean("isInternetOn", false) &&
                    !settingsInfo.optBoolean("isBatteryOptimized", true)
                )
                put("permissionsGranted", hasBackgroundLocationPermission())
                put("needsPermission", !hasBackgroundLocationPermission())
            })
            
            // Add recommendations (what's missing)
            put("recommendations", getRecommendationsNoRequest(permissionsInfo, settingsInfo))
            
            // Add metadata
            put("metadata", JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkVersion", Build.VERSION.SDK_INT)
                put("packageName", cordova.activity.packageName)
                put("dataType", "no_permission_request")
                put("autoRequestDisabled", true)
            })
        }
        
        locationCallbackContext?.success(allDeviceData)
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error in getDeviceDataNoPermissionRequest: ${e.message}")
        locationCallbackContext?.error("Error getting device data: ${e.message}")
    }
}

// Helper: Get location data safely without throwing exceptions
private fun getLocationDataSyncSafe(): JSONObject {
    return try {
        val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && 
            !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return createNoLocationData("Location services disabled")
        }
        
        var location: Location? = null
        
        if (ActivityCompat.checkSelfPermission(cordova.activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
        }
        
        if (location == null && ActivityCompat.checkSelfPermission(cordova.activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        }
        
        if (location != null) {
            JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("timestamp", location.time)
                put("provider", location.provider ?: "unknown")
                put("available", true)
                put("age", System.currentTimeMillis() - location.time)
            }
        } else {
            createNoLocationData("No recent location available")
        }
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error getting location safely: ${e.message}")
        createNoLocationData("Error: ${e.message}")
    }
}

// Helper: Create no location response
private fun createNoLocationData(reason: String): JSONObject {
    return JSONObject().apply {
        put("error", reason)
        put("available", false)
        put("latitude", null)
        put("longitude", null)
        put("needsPermission", !hasLocationPermission())
    }
}

// Helper: Get recommendations without requesting permissions
private fun getRecommendationsNoRequest(permissions: JSONObject, settings: JSONObject): JSONArray {
    val recommendations = JSONArray()
    
    if (!permissions.optBoolean("fineLocation", false) && !permissions.optBoolean("coarseLocation", false)) {
        recommendations.put("Location permission required - tap 'Grant Permissions' to enable")
    } else if (!permissions.optBoolean("backgroundLocation", false)) {
        recommendations.put("Background location permission required - tap 'Grant Permissions' to enable")
    }
    
    if (!settings.optBoolean("isLocationEnabled", false)) {
        recommendations.put("Turn ON Location Services in device settings")
    }
    
    if (!settings.optBoolean("isInternetOn", false)) {
        recommendations.put("Connect to WiFi or enable Mobile Data")
    }
    
    if (settings.optBoolean("isBatteryOptimized", true)) {
        recommendations.put("Disable Battery Optimization for better background tracking")
    }
    
    if (recommendations.length() == 0) {
        recommendations.put("All settings configured correctly!")
    }
    
    return recommendations
}
    
 private fun hasLocationPermission(): Boolean {
    val fineLocationGranted = ContextCompat.checkSelfPermission(
        cordova.activity, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    
    val coarseLocationGranted = ContextCompat.checkSelfPermission(
        cordova.activity, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    
    android.util.Log.d("MyKotlinPlugin", "Fine location: $fineLocationGranted, Coarse location: $coarseLocationGranted")
    
    return fineLocationGranted || coarseLocationGranted
}
    
    private fun requestLocationPermission() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        cordova.requestPermissions(this, LOCATION_PERMISSION_REQUEST, permissions)
    }
    
    private fun getLocation() {
        try {
            val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Check if location services are enabled
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && 
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationCallbackContext?.error("Location services are disabled")
                return
            }
            
            // Check permissions again (double check)
            if (ActivityCompat.checkSelfPermission(
                    cordova.activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && 
                ActivityCompat.checkSelfPermission(
                    cordova.activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                locationCallbackContext?.error("Location permissions not granted")
                return
            }
            
            // Try to get last known location first (faster)
            var location: Location? = null
            
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            
            if (location != null) {
                val locationData = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    put("timestamp", location.time)
                    put("provider", location.provider ?: "unknown")
                }
                
                locationCallbackContext?.success(locationData)
            } else {
                locationCallbackContext?.error("Unable to get current location")
            }
            
        } catch (e: Exception) {
            locationCallbackContext?.error("Error getting location: ${e.message}")
        }
    }
    
override fun onRequestPermissionResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?) {
    android.util.Log.d("MyKotlinPlugin", "Permission result: requestCode=$requestCode")
    
    when (requestCode) {
        LOCATION_PERMISSION_REQUEST -> {
            if (grantResults != null && grantResults.isNotEmpty() && 
                grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                
                android.util.Log.d("MyKotlinPlugin", "Basic location permission granted")
                
                // If we have pending start request, continue with background permission
                if (pendingStartCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                    val backgroundPermissions = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    cordova.requestPermissions(this, BACKGROUND_LOCATION_PERMISSION_REQUEST, backgroundPermissions)
                    return
                }
                
                // If all permissions good, start service or return success
                if (pendingStartCallback != null && hasAllRequiredPermissions()) {
                    startBackgroundTrackingService(JSONObject())
                } else {
                    returnPermissionStatus(hasAllRequiredPermissions(), "Basic location permission granted")
                }
                
            } else {
                handlePermissionDenied("Basic location permission denied")
            }
        }
        
        BACKGROUND_LOCATION_PERMISSION_REQUEST -> {
            if (grantResults != null && grantResults.isNotEmpty() && 
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                
                android.util.Log.d("MyKotlinPlugin", "Background permission granted")
                
                if (pendingStartCallback != null) {
                    startBackgroundTrackingService(JSONObject())
                } else {
                    returnPermissionStatus(true, "All permissions granted")
                }
                
            } else {
                handlePermissionDenied("Background location permission denied")
            }
        }
    }
    
    // Clear callbacks after handling
    if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST || hasAllRequiredPermissions()) {
        pendingStartCallback = null
    }
}

// Handle permission denied cases
private fun handlePermissionDenied(message: String) {
    permissionRetryCount++
    
    if (permissionRetryCount < MAX_PERMISSION_RETRIES) {
        android.util.Log.d("MyKotlinPlugin", "Retrying permissions (attempt $permissionRetryCount)")
        // Retry after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            requestAllRequiredPermissions()
        }, 2000)
    } else {
        // Max retries reached
        val errorMsg = "$message. Please enable permissions manually in Settings."
        pendingStartCallback?.error(errorMsg) ?: locationCallbackContext?.error(errorMsg)
        pendingStartCallback = null
        locationCallbackContext = null
    }
}

    
    private fun clearStoredData() {
        storedBatteryInfo = null
        storedPermissionsInfo = null
        storedSettingsInfo = null
    }
    private fun returnDataWithPermissionNeeded(batteryInfo: JSONObject, permissionsInfo: JSONObject, settingsInfo: JSONObject, locationData: JSONObject, status: String) {
    val allDeviceData = JSONObject().apply {
        put("battery", batteryInfo)
        put("location", locationData)
        put("permissions", permissionsInfo)
        put("settings", settingsInfo)
        
        put("summary", JSONObject().apply {
            put("canTrackLocation", hasLocationPermission())
            put("canTrackBackground", hasBackgroundLocationPermission())
            put("isLocationAvailable", locationData.optBoolean("available", false))
            put("batteryLevel", batteryInfo.optInt("percentage", 0))
            put("isCharging", batteryInfo.optBoolean("isCharging", false))
            put("hasInternet", settingsInfo.optBoolean("isInternetOn", false))
            put("isFullyReady", false)
            put("permissionStatus", status)
            put("needsPermission", true)
            put("autoRequestPermission", true) // Signal to JS to request again
        })
        
        put("recommendations", JSONArray().apply {
            when (status) {
                "basic_location_needed" -> {
                    put("Please allow location access for this app to work")
                    put("Select 'Allow' when prompted for location permission")
                }
                "background_location_needed" -> {
                    put("Please select 'Allow all the time' for background location tracking")
                    put("This enables the app to work even when closed")
                }
            }
        })
        
        put("metadata", JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("permissionRequestNeeded", true)
            put("status", status)
        })
    }
    
    locationCallbackContext?.success(allDeviceData)
}

    private fun returnDataWithPermissionStatus(batteryInfo: JSONObject, settingsInfo: JSONObject, permissionStatus: String) {
    val permissionsInfo = getPermissionsStatusSync()
    val locationData = JSONObject().apply {
        put("error", "Location permission required")
        put("available", false)
        put("permissionStatus", permissionStatus)
    }
    
    val allDeviceData = JSONObject().apply {
        put("battery", batteryInfo)
        put("location", locationData)
        put("permissions", permissionsInfo)
        put("settings", settingsInfo)
        
        put("summary", JSONObject().apply {
            put("canTrackLocation", hasLocationPermission())
            put("canTrackBackground", hasBackgroundLocationPermission())
            put("isLocationAvailable", false)
            put("batteryLevel", batteryInfo.optInt("percentage", 0))
            put("isCharging", batteryInfo.optBoolean("isCharging", false))
            put("hasInternet", settingsInfo.optBoolean("isInternetOn", false))
            put("internetType", settingsInfo.optString("internetType", "None"))
            put("isBatteryOptimized", settingsInfo.optBoolean("isBatteryOptimized", true))
            put("isFullyReady", false)
            put("permissionStatus", permissionStatus)
            put("needsPermission", true)
        })
        
        put("recommendations", getRecommendationsWithPermission(permissionsInfo, settingsInfo, permissionStatus))
        
        put("metadata", JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("packageName", cordova.activity.packageName)
            put("permissionDenied", true)
        })
    }
    
    locationCallbackContext?.success(allDeviceData)
}
private fun getRecommendationsWithPermission(permissions: JSONObject, settings: JSONObject, permissionStatus: String): JSONArray {
    val recommendations = JSONArray()
    
    when (permissionStatus) {
        "basic_location_denied" -> {
            recommendations.put("CRITICAL: Allow location permission to use this app")
            recommendations.put("Go to Settings > Apps > [Your App] > Permissions > Location > Allow")
        }
        "background_location_denied" -> {
            recommendations.put("CRITICAL: Enable 'Allow all the time' location permission for background tracking")
            recommendations.put("Go to Settings > Apps > [Your App] > Permissions > Location > Allow all the time")
        }
    }
    
    // Add other recommendations
    if (!settings.optBoolean("isInternetOn", false)) {
        recommendations.put("Connect to WiFi or enable Mobile Data")
    }
    
    if (settings.optBoolean("isBatteryOptimized", true)) {
        recommendations.put("Disable Battery Optimization for this app")
    }
    
    return recommendations
}
    
    private fun getBatteryInfo(callbackContext: CallbackContext?) {
        try {
            val batteryIntent = cordova.activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            
            if (batteryIntent != null) {
                // Get battery level
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPercentage = (level * 100 / scale.toFloat()).toInt()
                
                // Get charging status
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                                status == BatteryManager.BATTERY_STATUS_FULL
                
                // Get charging type
                val chargePlug = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val chargingType = when (chargePlug) {
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> "Not charging"
                }
                
                // Get battery health
                val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val healthStatus = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                    else -> "Unknown"
                }
                
                // Get battery temperature (in Celsius)
                val temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
                
                // Get battery voltage
                val voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000.0
                
                val batteryInfo = JSONObject().apply {
                    put("percentage", batteryPercentage)
                    put("isCharging", isCharging)
                    put("chargingType", chargingType)
                    put("health", healthStatus)
                    put("temperature", temperature)
                    put("voltage", voltage)
                }
                
                callbackContext?.success(batteryInfo)
            } else {
                callbackContext?.error("Unable to get battery information")
            }
        } catch (e: Exception) {
            callbackContext?.error("Error getting battery info: ${e.message}")
        }
    }
    
    private fun getBatteryPercentage(callbackContext: CallbackContext?) {
        try {
            val batteryIntent = cordova.activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPercentage = (level * 100 / scale.toFloat()).toInt()
                
                callbackContext?.success(batteryPercentage)
            } else {
                callbackContext?.error("Unable to get battery percentage")
            }
        } catch (e: Exception) {
            callbackContext?.error("Error getting battery percentage: ${e.message}")
        }
    }
    
    private fun getBatteryChargingStatus(callbackContext: CallbackContext?) {
        try {
            val batteryIntent = cordova.activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            
            if (batteryIntent != null) {
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                                status == BatteryManager.BATTERY_STATUS_FULL
                
                val chargePlug = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val chargingType = when (chargePlug) {
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> "Not charging"
                }
                
                val chargingInfo = JSONObject().apply {
                    put("isCharging", isCharging)
                    put("chargingType", chargingType)
                }
                
                callbackContext?.success(chargingInfo)
            } else {
                callbackContext?.error("Unable to get charging status")
            }
        } catch (e: Exception) {
            callbackContext?.error("Error getting charging status: ${e.message}")
        }
    }
    
    private fun getDeviceInfo() {
        // First get battery info (doesn't require permissions)
        val batteryInfo = getBatteryInfoSync()
        
        // Then check location permissions and get location
        if (hasLocationPermission()) {
            getLocationWithBattery(batteryInfo)
        } else {
            requestLocationPermission()
        }
    }
    
    private fun getBatteryInfoSync(): JSONObject {
        val batteryIntent = cordova.activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryInfo = JSONObject()
        
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPercentage = (level * 100 / scale.toFloat()).toInt()
            
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                            status == BatteryManager.BATTERY_STATUS_FULL
            
            val chargePlug = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargingType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Not charging"
            }
            
            val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val healthStatus = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }
            
            val temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
            val voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000.0
            
            batteryInfo.apply {
                put("percentage", batteryPercentage)
                put("isCharging", isCharging)
                put("chargingType", chargingType)
                put("health", healthStatus)
                put("temperature", temperature)
                put("voltage", voltage)
            }
        }
        
        return batteryInfo
    }
    
    private fun getLocationWithBattery(batteryInfo: JSONObject) {
        try {
            val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && 
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                
                // Return battery info even if location fails
                val result = JSONObject().apply {
                    put("battery", batteryInfo)
                    put("location", JSONObject().apply {
                        put("error", "Location services are disabled")
                    })
                }
                locationCallbackContext?.success(result)
                return
            }
            
            if (ActivityCompat.checkSelfPermission(
                    cordova.activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && 
                ActivityCompat.checkSelfPermission(
                    cordova.activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                val result = JSONObject().apply {
                    put("battery", batteryInfo)
                    put("location", JSONObject().apply {
                        put("error", "Location permissions not granted")
                    })
                }
                locationCallbackContext?.success(result)
                return
            }
            
            var location: Location? = null
            
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            
            val result = JSONObject().apply {
                put("battery", batteryInfo)
                if (location != null) {
                    put("location", JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("timestamp", location.time)
                        put("provider", location.provider ?: "unknown")
                    })
                } else {
                    put("location", JSONObject().apply {
                        put("error", "Unable to get current location")
                    })
                }
            }
            
            locationCallbackContext?.success(result)
            
        } catch (e: Exception) {
            val result = JSONObject().apply {
                put("battery", batteryInfo)
                put("location", JSONObject().apply {
                    put("error", "Error getting location: ${e.message}")
                })
            }
            locationCallbackContext?.success(result)
        }
    }
    
  private fun getAllDeviceData() {
    try {
        android.util.Log.d("MyKotlinPlugin", "getAllDeviceData called")
        
        // 1. Get battery info (no permissions needed)
        val batteryInfo = getBatteryInfoSync()
        
        // 2. Get current permissions status
        val permissionsInfo = getPermissionsStatusSync()
        
        // 3. Get device settings
        val settingsInfo = getDeviceSettingsSync()
        
        // 4. Check permissions and automatically request if needed
        val needsBasicLocationPermission = !hasLocationPermission()
        val needsBackgroundPermission = hasLocationPermission() && !hasBackgroundLocationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        
        if (needsBasicLocationPermission) {
            android.util.Log.d("MyKotlinPlugin", "Auto-requesting basic location permission")
            storeDataForCallback(batteryInfo, permissionsInfo, settingsInfo)
            requestLocationPermission()
            return
        }
        
        if (needsBackgroundPermission) {
            android.util.Log.d("MyKotlinPlugin", "Auto-requesting background location permission")
            storeDataForCallback(batteryInfo, permissionsInfo, settingsInfo)
            requestBackgroundLocationPermission()
            return
        }
        
        // We have all needed permissions, get location and return everything
        getLocationAndReturnAllData(batteryInfo, permissionsInfo, settingsInfo)
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error in getAllDeviceData: ${e.message}")
        locationCallbackContext?.error("Error getting device data: ${e.message}")
    }
}
    
    private var storedBatteryInfo: JSONObject? = null
    private var storedPermissionsInfo: JSONObject? = null
    private var storedSettingsInfo: JSONObject? = null
    
    private fun storeDataForCallback(battery: JSONObject, permissions: JSONObject, settings: JSONObject) {
        storedBatteryInfo = battery
        storedPermissionsInfo = permissions
        storedSettingsInfo = settings
    }
    
    private fun getLocationAndReturnAllData(batteryInfo: JSONObject, permissionsInfo: JSONObject, settingsInfo: JSONObject) {
        try {
              if (!hasLocationPermission()) {
            android.util.Log.w("MyKotlinPlugin", "No location permission in getLocationAndReturnAllData")
            val locationData = JSONObject().apply {
                put("error", "Location permissions not available")
                put("available", false)
            }
            returnSafeDeviceData(batteryInfo, permissionsInfo, settingsInfo, locationData)
            return
        }
            val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Check if location services are enabled
            val isLocationServicesEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                                          locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
            // Get location if possible
            var locationData = JSONObject()
            
            if (!isLocationServicesEnabled) {
                locationData.put("error", "Location services are disabled")
                locationData.put("available", false)
            } else if (!hasLocationPermission()) {
                locationData.put("error", "Location permissions not granted")
                locationData.put("available", false)
            } else {
                // Try to get location
                var location: Location? = null
                
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(cordova.activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    }
                }
                
                if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(cordova.activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    }
                }
                
                if (location != null) {
                    locationData.apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("timestamp", location.time)
                        put("provider", location.provider ?: "unknown")
                        put("available", true)
                        put("age", System.currentTimeMillis() - location.time) // How old is this location
                    }
                } else {
                    locationData.put("error", "No recent location available")
                    locationData.put("available", false)
                }
            }
            
            // Create comprehensive response
            val allDeviceData = JSONObject().apply {
                put("battery", batteryInfo)
                put("location", locationData)
                put("permissions", permissionsInfo)
                put("settings", settingsInfo)
                
                // Add summary status
                put("summary", JSONObject().apply {
                    put("canTrackLocation", hasLocationPermission() && isLocationServicesEnabled)
                    put("canTrackBackground", hasBackgroundLocationPermission() && isLocationServicesEnabled)
                    put("isLocationAvailable", locationData.optBoolean("available", false))
                    put("batteryLevel", batteryInfo.optInt("percentage", 0))
                    put("isCharging", batteryInfo.optBoolean("isCharging", false))
                    put("hasInternet", settingsInfo.optBoolean("isInternetOn", false))
                    put("internetType", settingsInfo.optString("internetType", "None"))
                    put("isBatteryOptimized", settingsInfo.optBoolean("isBatteryOptimized", true))
                    put("isFullyReady", 
                        hasBackgroundLocationPermission() && 
                        isLocationServicesEnabled && 
                        settingsInfo.optBoolean("isInternetOn", false) &&
                        !settingsInfo.optBoolean("isBatteryOptimized", true)
                    )
                })
                
                // Add recommendations
                put("recommendations", getRecommendations(permissionsInfo, settingsInfo, isLocationServicesEnabled))
                
                // Add metadata
                put("metadata", JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("packageName", cordova.activity.packageName)
                })
            }
            
            locationCallbackContext?.success(allDeviceData)
            
        } catch (e: Exception) {
            // Even if location fails, return what we can
            val fallbackData = JSONObject().apply {
                put("battery", batteryInfo)
                put("permissions", permissionsInfo)
                put("settings", settingsInfo)
                put("location", JSONObject().apply {
                    put("error", "Error getting location: ${e.message}")
                    put("available", false)
                })
                put("summary", JSONObject().apply {
                    put("error", "Partial data due to error: ${e.message}")
                })
            }
            locationCallbackContext?.success(fallbackData)
        }
    }
    private fun returnSafeDeviceData(batteryInfo: JSONObject, permissionsInfo: JSONObject, settingsInfo: JSONObject, locationData: JSONObject) {
    val allDeviceData = JSONObject().apply {
        put("battery", batteryInfo)
        put("location", locationData)
        put("permissions", permissionsInfo)
        put("settings", settingsInfo)
        
        put("summary", JSONObject().apply {
            put("canTrackLocation", false)
            put("canTrackBackground", false)
            put("isLocationAvailable", false)
            put("batteryLevel", batteryInfo.optInt("percentage", 0))
            put("isCharging", batteryInfo.optBoolean("isCharging", false))
            put("hasInternet", settingsInfo.optBoolean("isInternetOn", false))
            put("internetType", settingsInfo.optString("internetType", "None"))
            put("isBatteryOptimized", settingsInfo.optBoolean("isBatteryOptimized", true))
            put("isFullyReady", false)
            put("needsPermission", true)
        })
        
        put("recommendations", JSONArray().apply {
            put("Enable location permissions to use this app")
            put("Go to Settings > Apps > [Your App] > Permissions > Location")
        })
        
        put("metadata", JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("packageName", cordova.activity.packageName)
            put("safeMode", true)
        })
    }
    
    locationCallbackContext?.success(allDeviceData)
}
    
    private fun getRecommendations(permissions: JSONObject, settings: JSONObject, locationEnabled: Boolean): JSONArray {
        val recommendations = JSONArray()
        
        if (!permissions.optBoolean("backgroundLocation", false)) {
            recommendations.put("Enable 'Allow all the time' location permission for background tracking")
        }
        
        if (!locationEnabled) {
            recommendations.put("Turn ON Location Services in device settings")
        }
        
        if (!settings.optBoolean("isInternetOn", false)) {
            recommendations.put("Connect to WiFi or enable Mobile Data")
        }
        
        if (settings.optBoolean("isBatteryOptimized", true)) {
            recommendations.put("Disable Battery Optimization for this app to ensure background operation")
        }
        
        val batteryLevel = settings.optInt("batteryLevel", 100)
        if (batteryLevel < 20) {
            recommendations.put("Battery level is low (${batteryLevel}%) - consider charging")
        }
        
        if (!settings.optBoolean("isGpsEnabled", false) && locationEnabled) {
            recommendations.put("Enable GPS for more accurate location tracking")
        }
        
        return recommendations
    }
    
    private fun requestBackgroundLocationPermission() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
       
        
        // First check if we already have basic location permission
        if (!hasLocationPermission()) {
            // Step 1: Request basic location permissions first
            android.util.Log.d("MyKotlinPlugin", "Requesting basic location permissions first")
            val permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            cordova.requestPermissions(this, LOCATION_PERMISSION_REQUEST, permissions)
        } else {
            // Step 2: We have basic permissions, now request background
            android.util.Log.d("MyKotlinPlugin", "Requesting background location permission")
            val permissions = arrayOf(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            cordova.requestPermissions(this, BACKGROUND_LOCATION_PERMISSION_REQUEST, permissions)
        }
    } else {
        // For older versions, just request normal location permissions
        requestLocationPermission()
    }
    }
    
  private fun hasBackgroundLocationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val backgroundGranted = ContextCompat.checkSelfPermission(
            cordova.activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        android.util.Log.d("MyKotlinPlugin", "Background location permission: $backgroundGranted")
        
        // Must have both basic location AND background permission
        backgroundGranted && hasLocationPermission()
    } else {
        // For older versions, regular location permission is enough
        hasLocationPermission()
    }
}
    
    private fun getPermissionsStatus(callbackContext: CallbackContext?) {
        try {
            val permissions = JSONObject().apply {
                // Location permissions
                put("fineLocation", ContextCompat.checkSelfPermission(
                    cordova.activity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
                
                put("coarseLocation", ContextCompat.checkSelfPermission(
                    cordova.activity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put("backgroundLocation", ContextCompat.checkSelfPermission(
                        cordova.activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED)
                } else {
                    put("backgroundLocation", hasLocationPermission())
                }
                
                // Background location status
                put("canTrackBackground", hasBackgroundLocationPermission())
            }
            
            callbackContext?.success(permissions)
        } catch (e: Exception) {
            callbackContext?.error("Error getting permissions status: ${e.message}")
        }
    }
    
    private fun getDeviceSettings(callbackContext: CallbackContext?) {
        try {
            val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val connectivityManager = cordova.activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val powerManager = cordova.activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            val settings = JSONObject().apply {
                // Location settings
                put("isLocationEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                                       locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                put("isGpsEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                put("isNetworkLocationEnabled", locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                
                // Internet connectivity
                val isInternetOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val network = connectivityManager.activeNetwork
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val networkInfo = connectivityManager.activeNetworkInfo
                    networkInfo != null && networkInfo.isConnected
                }
                put("isInternetOn", isInternetOn)
                
                // Internet type
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
                
                // Battery optimization settings
                val packageName = cordova.activity.packageName
                val isBatteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    !powerManager.isIgnoringBatteryOptimizations(packageName)
                } else {
                    false
                }
                put("isBatteryOptimized", isBatteryOptimized)
                put("isBatteryRestricted", isBatteryOptimized)
                
                // App info
                put("packageName", packageName)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkVersion", Build.VERSION.SDK_INT)
            }
            
            callbackContext?.success(settings)
        } catch (e: Exception) {
            callbackContext?.error("Error getting device settings: ${e.message}")
        }
    }
    
    private fun getCompleteDeviceInfo() {
        val batteryInfo = getBatteryInfoSync()
        val permissionsInfo = getPermissionsStatusSync()
        val settingsInfo = getDeviceSettingsSync()
        
        if (hasLocationPermission()) {
            getLocationWithCompleteInfo(batteryInfo, permissionsInfo, settingsInfo)
        } else {
            requestLocationPermission()
        }
    }
    
    private fun getPermissionsStatusSync(): JSONObject {
        return JSONObject().apply {
            put("fineLocation", ContextCompat.checkSelfPermission(
                cordova.activity, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
            
            put("coarseLocation", ContextCompat.checkSelfPermission(
                cordova.activity, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put("backgroundLocation", ContextCompat.checkSelfPermission(
                    cordova.activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
            } else {
                put("backgroundLocation", hasLocationPermission())
            }
            
            put("canTrackBackground", hasBackgroundLocationPermission())
        }
    }
    
    private fun getDeviceSettingsSync(): JSONObject {
        val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val connectivityManager = cordova.activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val powerManager = cordova.activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        return JSONObject().apply {
            put("isLocationEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                                   locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            put("isGpsEnabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            put("isNetworkLocationEnabled", locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            
            val isInternetOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
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
            
            val packageName = cordova.activity.packageName
            val isBatteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !powerManager.isIgnoringBatteryOptimizations(packageName)
            } else {
                false
            }
            put("isBatteryOptimized", isBatteryOptimized)
            put("isBatteryRestricted", isBatteryOptimized)
            
            put("packageName", packageName)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
        }
    }
    
    private fun getLocationWithCompleteInfo(batteryInfo: JSONObject, permissionsInfo: JSONObject, settingsInfo: JSONObject) {
        try {
            val locationManager = cordova.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && 
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                
                val result = JSONObject().apply {
                    put("battery", batteryInfo)
                    put("permissions", permissionsInfo)
                    put("settings", settingsInfo)
                    put("location", JSONObject().apply {
                        put("error", "Location services are disabled")
                    })
                }
                locationCallbackContext?.success(result)
                return
            }
            
            if (ActivityCompat.checkSelfPermission(
                    cordova.activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && 
                ActivityCompat.checkSelfPermission(
                    cordova.activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                val result = JSONObject().apply {
                    put("battery", batteryInfo)
                    put("permissions", permissionsInfo)
                    put("settings", settingsInfo)
                    put("location", JSONObject().apply {
                        put("error", "Location permissions not granted")
                    })
                }
                locationCallbackContext?.success(result)
                return
            }
            
            var location: Location? = null
            
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            
            val result = JSONObject().apply {
                put("battery", batteryInfo)
                put("permissions", permissionsInfo)
                put("settings", settingsInfo)
                if (location != null) {
                    put("location", JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("timestamp", location.time)
                        put("provider", location.provider ?: "unknown")
                    })
                } else {
                    put("location", JSONObject().apply {
                        put("error", "Unable to get current location")
                    })
                }
            }
            
            locationCallbackContext?.success(result)
            
        } catch (e: Exception) {
            val result = JSONObject().apply {
                put("battery", batteryInfo)
                put("permissions", permissionsInfo)
                put("settings", settingsInfo)
                put("location", JSONObject().apply {
                    put("error", "Error getting location: ${e.message}")
                })
            }
            locationCallbackContext?.success(result)
        }
    }


    // 1. START TRACKING WITH PERMISSIONS
private fun startTrackingWithPermissions(extraData: JSONObject) {
    android.util.Log.d("MyKotlinPlugin", "Starting tracking with permissions check")
    permissionRetryCount = 0
    
    if (hasAllRequiredPermissions()) {
        startBackgroundTrackingService(extraData)
    } else {
        requestAllRequiredPermissions()
    }
}

// 2. STOP TRACKING
private fun stopBackgroundTracking(callbackContext: CallbackContext?) {
    try {
        android.util.Log.d("MyKotlinPlugin", "Stopping background tracking")
        
        // Send stop intent with action
        val stopIntent = Intent(cordova.activity, LocationForegroundService::class.java)
        stopIntent.action = "STOP_SERVICE"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            cordova.activity.startForegroundService(stopIntent)
        } else {
            cordova.activity.startService(stopIntent)
        }
        
        // Also try to stop it directly
        Thread.sleep(100) // Small delay to let the service process the stop action
        val stopDirectIntent = Intent(cordova.activity, LocationForegroundService::class.java)
        cordova.activity.stopService(stopDirectIntent)
        
        isTrackingActive = false
        
        // Clear stored data
        val sharedPrefs = cordova.activity.getSharedPreferences("LocationServicePrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().apply()
        
        callbackContext?.success(JSONObject().apply {
            put("status", "stopped")
            put("message", "Background tracking stopped successfully")
            put("timestamp", System.currentTimeMillis())
        })
        
        android.util.Log.d("MyKotlinPlugin", "Background tracking stopped successfully")
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error stopping tracking: ${e.message}")
        callbackContext?.error("Error stopping tracking: ${e.message}")
    }
}

// 3. CHECK AND REQUEST PERMISSIONS
private fun checkAndRequestAllPermissions() {
    android.util.Log.d("MyKotlinPlugin", "Checking and requesting all permissions")
    
    if (hasAllRequiredPermissions()) {
        returnPermissionStatus(true, "All permissions granted")
    } else {
        requestAllRequiredPermissions()
    }
}

// Helper: Check if all permissions are granted
private fun hasAllRequiredPermissions(): Boolean {
    val basicLocation = hasLocationPermission()
    val backgroundLocation = hasBackgroundLocationPermission()
    return basicLocation && backgroundLocation
}

// Helper: Request all permissions
private fun requestAllRequiredPermissions() {
    if (!hasLocationPermission()) {
        android.util.Log.d("MyKotlinPlugin", "Requesting basic location permissions")
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        cordova.requestPermissions(this, LOCATION_PERMISSION_REQUEST, permissions)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
        android.util.Log.d("MyKotlinPlugin", "Requesting background location permission")
        val permissions = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        cordova.requestPermissions(this, BACKGROUND_LOCATION_PERMISSION_REQUEST, permissions)
    }
}

// Start the actual background service
private fun startBackgroundTrackingService(extraData: JSONObject) {
    try {
        val intent = Intent(cordova.activity, LocationForegroundService::class.java)
        intent.putExtra("extraData", extraData.toString())
        cordova.activity.startForegroundService(intent)
        isTrackingActive = true
        
        pendingStartCallback?.success(JSONObject().apply {
            put("status", "started")
            put("message", "Background tracking started successfully")
        })
        
    } catch (e: Exception) {
        pendingStartCallback?.error("Error starting tracking: ${e.message}")
    }
}

// Get current tracking status
private fun getTrackingStatus(callbackContext: CallbackContext?) {
    val status = JSONObject().apply {
        put("isTracking", isTrackingActive)
        put("hasBasicPermission", hasLocationPermission())
        put("hasBackgroundPermission", hasBackgroundLocationPermission())
        put("allPermissionsGranted", hasAllRequiredPermissions())
    }
    callbackContext?.success(status)
}
private fun openLocationPermissions(callbackContext: CallbackContext?) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${cordova.activity.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        cordova.activity.startActivity(intent)
        
        // Show helpful message
        Handler(Looper.getMainLooper()).postDelayed({
            android.widget.Toast.makeText(
                cordova.activity,
                "Go to Permissions → Location → Select 'Allow all the time'",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }, 500)
        
        callbackContext?.success("App settings opened - Go to Permissions → Location")
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error opening location settings: ${e.message}")
        callbackContext?.error("Cannot open app settings: ${e.message}")
    }
}

private fun openBatteryOptimizationSettings(callbackContext: CallbackContext?) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = cordova.activity.packageName
            val powerManager = cordova.activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Try direct battery optimization request first
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                try {
                    cordova.activity.startActivity(intent)
                    callbackContext?.success("Battery optimization request opened - Select 'Allow'")
                    return
                } catch (e: Exception) {
                    android.util.Log.w("MyKotlinPlugin", "Direct battery request failed, trying settings")
                }
            }
            
            // Fallback to battery optimization settings
            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            cordova.activity.startActivity(settingsIntent)
            
            Handler(Looper.getMainLooper()).postDelayed({
                android.widget.Toast.makeText(
                    cordova.activity,
                    "Find your app and select 'Don't optimize'",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }, 500)
            
            callbackContext?.success("Battery optimization settings opened")
            
        } else {
            callbackContext?.error("Battery optimization not available on Android ${Build.VERSION.RELEASE}")
        }
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error with battery settings: ${e.message}")
        callbackContext?.error("Cannot open battery settings: ${e.message}")
    }
}

private fun openLocationServicesSettings(callbackContext: CallbackContext?) {
    try {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        cordova.activity.startActivity(intent)
        
        Handler(Looper.getMainLooper()).postDelayed({
            android.widget.Toast.makeText(
                cordova.activity,
                "Turn ON Location Services",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }, 500)
        
        callbackContext?.success("Location services settings opened")
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error opening location services: ${e.message}")
        callbackContext?.error("Cannot open location services: ${e.message}")
    }
}

private fun openPermissionSettings(callbackContext: CallbackContext?) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${cordova.activity.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        cordova.activity.startActivity(intent)
        callbackContext?.success("App permission settings opened")
        
    } catch (e: Exception) {
        android.util.Log.e("MyKotlinPlugin", "Error opening permission settings: ${e.message}")
        callbackContext?.error("Cannot open permission settings: ${e.message}")
    }
}

// Return permission status
private fun returnPermissionStatus(allGranted: Boolean, message: String) {
    val response = JSONObject().apply {
        put("allPermissionsGranted", allGranted)
        put("hasBasicLocation", hasLocationPermission())
        put("hasBackgroundLocation", hasBackgroundLocationPermission())
        put("message", message)
    }
    locationCallbackContext?.success(response)
}
}