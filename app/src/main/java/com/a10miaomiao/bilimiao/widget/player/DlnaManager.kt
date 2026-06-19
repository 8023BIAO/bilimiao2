package com.a10miaomiao.bilimiao.widget.player

import android.content.Context
import android.net.wifi.WifiManager
import com.a10miaomiao.bilimiao.comm.utils.miaoLogger
import kotlinx.coroutines.*
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.model.message.header.STAllHeader
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.registry.Registry
import org.jupnp.registry.RegistryListener
import org.jupnp.support.avtransport.callback.Play
import org.jupnp.support.avtransport.callback.SetAVTransportURI
import org.jupnp.support.avtransport.callback.Stop
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse

class DlnaManager(private val context: Context) {

    companion object {
        private const val TAG = "DlnaManager"
    }

    private var upnpService: UpnpService? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _devices = mutableListOf<DlnaDevice>()
    val devices: List<DlnaDevice> get() = _devices.toList()
    val hasDevices: Boolean get() = _devices.isNotEmpty()

    var onDevicesChanged: ((List<DlnaDevice>) -> Unit)? = null

    private val registryListener = object : RegistryListener {
        override fun remoteDeviceDiscoveryStarted(registry: Registry, device: RemoteDevice) {}
        override fun remoteDeviceDiscoveryFailed(registry: Registry, device: RemoteDevice, ex: Exception?) {}
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
            if (device.type.type == "MediaRenderer") {
                val dlnaDevice = DlnaDevice(
                    udn = device.identity.udn.identifierString,
                    name = device.details?.friendlyName ?: "未知设备",
                    device = device
                )
                synchronized(_devices) {
                    if (_devices.none { it.udn == dlnaDevice.udn }) {
                        _devices.add(dlnaDevice)
                        notifyChanged()
                    }
                }
                miaoLogger().d(TAG, "发现设备: ${dlnaDevice.name}")
            }
        }

        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
            synchronized(_devices) {
                _devices.removeAll { it.udn == device.identity.udn.identifierString }
                notifyChanged()
            }
        }

        override fun remoteDeviceUpdated(registry: Registry, device: RemoteDevice) {}
        override fun localDeviceAdded(registry: Registry, device: org.jupnp.model.meta.LocalDevice) {}
        override fun localDeviceRemoved(registry: Registry, device: org.jupnp.model.meta.LocalDevice) {}
        override fun beforeShutdown(registry: Registry) {}
        override fun afterShutdown() {}
    }

    private fun notifyChanged() {
        val list = devices
        scope.launch(Dispatchers.Main) {
            onDevicesChanged?.invoke(list)
        }
    }

    fun startDiscovery() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled != true) {
            miaoLogger().d(TAG, "WiFi未连接，跳过DLNA发现")
            return
        }
        if (upnpService != null) return
        try {
            upnpService = UpnpServiceImpl().apply {
                registry.addListener(registryListener)
                controlPoint.search(STAllHeader())
            }
            miaoLogger().d(TAG, "DLNA设备发现已启动")
        } catch (e: Exception) {
            miaoLogger().e(TAG, "DLNA服务启动失败: ${e.message}", e)
        }
    }

    fun stopDiscovery() {
        try {
            upnpService?.registry?.removeListener(registryListener)
            upnpService?.shutdown()
        } catch (_: Exception) {}
        upnpService = null
        _devices.clear()
    }

    fun castToDevice(
        device: DlnaDevice,
        videoUrl: String,
        title: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val avService = device.device.findService(
            org.jupnp.model.types.ServiceType("urn:schemas-upnp-org:service", "AVTransport:1")
        ) ?: run {
            onResult?.invoke(false, "设备不支持AVTransport")
            return
        }
        val metadata = buildDIDLMetadata(title, videoUrl)
        upnpService?.controlPoint?.execute(
            object : SetAVTransportURI(avService, videoUrl, metadata) {
                override fun success(invocation: ActionInvocation<*>?) {
                    upnpService?.controlPoint?.execute(
                        object : Play(avService) {
                            override fun success(invocation: ActionInvocation<*>?) {}
                            override fun failure(invocation: ActionInvocation<*>?, operation: UpnpResponse?, defaultMsg: String?) {}
                        }
                    )
                    onResult?.invoke(true, "投屏成功")
                }
                override fun failure(invocation: ActionInvocation<*>?, operation: UpnpResponse?, defaultMsg: String?) {
                    onResult?.invoke(false, defaultMsg ?: "投屏失败")
                }
            }
        )
    }

    private fun buildDIDLMetadata(title: String, url: String): String {
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" 
                       xmlns:dc="http://purl.org/dc/elements/1.1/" 
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="0" parentID="-1" restricted="1">
                    <dc:title>${title.replace("&", "&amp;").replace("<", "&lt;")}</dc:title>
                    <res protocolInfo="http-get:*:video/*:DLNA.ORG_OP=01;DLNA.ORG_CI=0">$url</res>
                    <upnp:class>object.item.videoItem</upnp:class>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    fun stopCast(device: DlnaDevice) {
        val avService = device.device.findService(
            org.jupnp.model.types.ServiceType("urn:schemas-upnp-org:service", "AVTransport:1")
        ) ?: return
        upnpService?.controlPoint?.execute(
            object : Stop(avService) {
                override fun success(invocation: ActionInvocation<*>?) {}
                override fun failure(invocation: ActionInvocation<*>?, operation: UpnpResponse?, defaultMsg: String?) {}
            }
        )
    }

    fun destroy() {
        stopDiscovery()
        scope.cancel()
    }
}

data class DlnaDevice(
    val udn: String,
    val name: String,
    val device: RemoteDevice
)
