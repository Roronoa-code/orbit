package com.mani.health.integration.samsungsensor

sealed class SensorSdkHeartRateException(message: String) : Exception(message) {
    class PermissionRequired : SensorSdkHeartRateException(
        "Heart-rate permission is required",
    )

    class TrackerUnsupported : SensorSdkHeartRateException(
        "HEART_RATE_CONTINUOUS is not supported",
    )

    class SdkPolicyRejected : SensorSdkHeartRateException(
        "Samsung Health Sensor SDK policy rejected HEART_RATE_CONTINUOUS",
    )

    class ServiceUnavailable : SensorSdkHeartRateException(
        "Samsung Health Sensor Service is unavailable",
    )

    class ServiceUpdateRequired : SensorSdkHeartRateException(
        "Samsung Health Sensor Service must be updated",
    )

    class SdkMissing : SensorSdkHeartRateException(
        "Samsung Health Sensor SDK binary is not installed",
    )

    class SessionAlreadyActive : SensorSdkHeartRateException(
        "A Samsung heart-rate session is already active",
    )

    class CallbackBufferOverflow : SensorSdkHeartRateException(
        "The heart-rate callback buffer is full",
    )

    class Unexpected(val errorClass: String) : SensorSdkHeartRateException(
        "Unexpected Samsung heart-rate source failure: $errorClass",
    ) {
        init {
            require(errorClass.isNotBlank()) { "Error class must not be blank" }
        }
    }
}
