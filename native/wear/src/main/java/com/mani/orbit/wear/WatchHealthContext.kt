package com.mani.orbit.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.mani.orbit.sync.HealthContext
import com.mani.orbit.sync.HealthContextWire
import com.mani.orbit.sync.ReadingWire
import com.mani.orbit.sync.PeerProtocol
import com.mani.orbit.sync.WireFamily
import java.util.concurrent.TimeUnit

/** The signed Data Layer's local cache survives disconnection; accept only the known phone owner. */
internal fun loadWatchHealthContext(context: Context): HealthContext? = loadPhoneData(context, HealthContextWire.PATH)?.let(HealthContextWire::decode)

internal fun loadPhoneData(context: Context, path: String): ByteArray? {
    val store = WatchStore(context)
    val phones = Tasks.await(Wearable.getCapabilityClient(context).getCapability(ReadingWire.PHONE_CAPABILITY,
        CapabilityClient.FILTER_ALL), 10, TimeUnit.SECONDS).nodes
    require(phones.size <= 1) { "Connect one Orbit phone to view sleep and energy" }
    val phone = phones.singleOrNull()?.id?.also { store.status("phone", it) } ?: store.status("phone").takeIf { it.isNotBlank() } ?: return null
    val items = Tasks.await(Wearable.getDataClient(context).dataItems, 10, TimeUnit.SECONDS)
    try {
        val matches = items.filter { it.uri.path == path && it.uri.host == phone }
        require(matches.size <= 1)
        return matches.singleOrNull()?.data
    } finally { items.release() }
}

internal fun requestPhoneHealthContext(context: Context, family: WireFamily = WireFamily.CONTEXT) {
    val capabilities = Tasks.await(Wearable.getCapabilityClient(context).getAllCapabilities(
        CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS)
    val phones = capabilities[ReadingWire.PHONE_CAPABILITY]?.nodes.orEmpty()
    require(phones.size == 1) { "Phone unavailable" }
    val phone = phones.single().id
    PeerProtocol.support(capabilities.filterValues { info -> info.nodes.any { it.id == phone } }.keys).require(family, "phone", "Watch")
    Tasks.await(Wearable.getMessageClient(context).sendMessage(phone, HealthContextWire.REQUEST_PATH, byteArrayOf()), 10, TimeUnit.SECONDS)
}
