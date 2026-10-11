package com.castlink.receiver.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.castlink.receiver.model.Device
import com.castlink.receiver.model.Tier

/**
 * 封装 NsdManager：
 * - registerAll：注册 _castlink._tcp（port 8755）。_airplay._tcp 由移植的
 *   airplay/MdnsService.kt 负责（AirPlayService 持有），这里不再占位注册，
 *   避免重复广播。
 * - discover：发现 _castlink._tcp，resolve 成功后回调 Device。
 * 所有失败只打 Log，不抛异常。
 */
class NsdHelper {

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val registeredListeners = mutableListOf<NsdManager.RegistrationListener>()

    fun registerAll(context: Context, deviceName: String, pin: String) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable")
            return
        }
        nsdManager = manager

        // _airplay._tcp 不再在这里占位注册：移植的 airplay/MdnsService.kt
        //（由 AirPlayService 持有）负责真实的 AirPlay 广播。

        val castlink = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE_CASTLINK
            port = 8755
            setAttribute("tier", "mainstream")
            setAttribute("pin", pin)
        }
        registerService(manager, castlink)
    }

    private fun registerService(manager: NsdManager, info: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered: ${info.serviceType} as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "register failed: ${info.serviceType} code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "unregistered: ${info.serviceType}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregister failed: code=$errorCode")
            }
        }
        registeredListeners.add(listener)
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
        }
    }

    fun discover(context: Context, onDevice: (Device) -> Unit) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable")
            return
        }
        nsdManager = manager
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveService(manager, serviceInfo, onDevice)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "service lost: ${serviceInfo.serviceName}")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "startDiscovery failed: $serviceType code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stopDiscovery failed: $serviceType code=$errorCode")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE_CASTLINK, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
        }
    }

    private fun resolveService(
        manager: NsdManager,
        info: NsdServiceInfo,
        onDevice: (Device) -> Unit
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed: ${serviceInfo.serviceName} code=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                try {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val tierAttr = serviceInfo.attributes?.get("tier")
                        ?.let { String(it, Charsets.UTF_8) }
                        ?: "mainstream"
                    val tier = when (tierAttr.lowercase()) {
                        "flagship" -> Tier.FLAGSHIP
                        "legacy" -> Tier.LEGACY
                        else -> Tier.MAINSTREAM
                    }
                    val name = serviceInfo.serviceName ?: host
                    onDevice(Device(name = name, ip = host, signalLevel = 3, tier = tier))
                } catch (e: Exception) {
                    Log.w(TAG, "onServiceResolved threw", e)
                }
            }
        }
        try {
            manager.resolveService(info, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "resolveService threw", e)
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery threw", e)
        }
        discoveryListener = null
    }

    fun unregisterAll() {
        val manager = nsdManager
        for (listener in registeredListeners) {
            try {
                manager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterService threw", e)
            }
        }
        registeredListeners.clear()
        stopDiscovery()
        nsdManager = null
    }

    companion object {
        private const val TAG = "NsdHelper"
        private const val SERVICE_TYPE_CASTLINK = "_castlink._tcp"
    }
}
