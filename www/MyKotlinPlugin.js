var exec = require('cordova/exec');

// NEW: Single function to get ALL device data with automatic permission handling
exports.getAllDeviceData = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getAllDeviceData', []);
};

// Legacy methods still available
exports.coolMethod = function(name, success, error) {
    exec(success, error, 'MyKotlinPlugin', 'coolMethod', [name]);
};
exports.startBackgroundTracking = function(success, error, extraData) {
    var extra = extraData || {};
    exec(success, error, 'MyKotlinPlugin', 'startBackgroundTracking', [extra]);
};
exports.getDeviceDataNoPermissionRequest = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getDeviceDataNoPermissionRequest', []);
}

exports.getCurrentLocation = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getCurrentLocation', []);
};

exports.getLocation = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getCurrentLocation', []);
};

exports.getBatteryInfo = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getBatteryInfo', []);
};

exports.getBatteryPercentage = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getBatteryPercentage', []);
};

exports.getBatteryChargingStatus = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getBatteryChargingStatus', []);
};

exports.getDeviceInfo = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getDeviceInfo', []);
};

exports.requestBackgroundLocation = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'requestBackgroundLocation', []);
};

exports.getPermissionsStatus = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getPermissionsStatus', []);
};

exports.getDeviceSettings = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getDeviceSettings', []);
};

exports.getCompleteDeviceInfo = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getCompleteDeviceInfo', []);
};

exports.startTracking = function(success, error, extraData) {
    var extra = extraData || {};
    exec(success, error, 'MyKotlinPlugin', 'startTracking', [extra]);
};

// 2. STOP TRACKING - Add this
exports.stopTracking = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'stopTracking', []);
};

// 3. CHECK PERMISSIONS - Add this
exports.checkAndRequestPermissions = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'checkAndRequestPermissions', []);
};

// Helper function - Add this
exports.getTrackingStatus = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'getTrackingStatus', []);
};


exports.openLocationPermissions = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'openLocationPermissions', []);
};

exports.openBatteryOptimizationSettings = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'openBatteryOptimizationSettings', []);
};

exports.openLocationServicesSettings = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'openLocationServicesSettings', []);
};

exports.openPermissionSettings = function(success, error) {
    exec(success, error, 'MyKotlinPlugin', 'openPermissionSettings', []);
};