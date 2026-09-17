package com.mani.health.integration.samsungsensor

import com.mani.health.core.model.SensorLeaseGate
import com.mani.health.core.model.deviceSensorLeaseGate

internal typealias ExclusiveLeaseGate = SensorLeaseGate
internal val samsungSensorConnectionGate = deviceSensorLeaseGate
internal val samsungHeartRateSessionGate = ExclusiveLeaseGate()
internal val samsungRawProbeSessionGate = ExclusiveLeaseGate()
