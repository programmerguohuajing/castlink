package com.castlink.receiver.dlna.service

/**
 * SOAPAction 路由与分发（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 输入：HTTP 控制路径（/control/avt|rc|cm）+ SOAPAction 头 + Body XML。
 * 输出：Pair(HTTP 状态码, 响应 Body XML)。
 * - 成功 → 200 + successEnvelope
 * - UpnpError → 500 + faultEnvelope（401/402/501/701）
 * - 未知路径/action → 500 + 401
 */
class SoapDispatcher(
    private val avt: AvTransportService,
    private val rc: RenderingControlService,
    private val cm: ConnectionManagerService,
    private val logger: (String) -> Unit = {},
) {

    fun dispatch(controlPath: String, soapActionHeader: String?, bodyXml: String): Pair<Int, String> {
        val action = SoapCodec.actionFromHeader(soapActionHeader)
        return try {
            if (action.isEmpty()) {
                return fault(500, 401, "Invalid Action: empty SOAPAction")
            }
            val (serviceType, outArgs) = when (controlPath) {
                AvTransportService.CONTROL_PATH ->
                    AvTransportService.SERVICE_TYPE to avt.handle(action, SoapCodec.parseArgs(bodyXml))
                RenderingControlService.CONTROL_PATH ->
                    RenderingControlService.SERVICE_TYPE to rc.handle(action, SoapCodec.parseArgs(bodyXml))
                ConnectionManagerService.CONTROL_PATH ->
                    ConnectionManagerService.SERVICE_TYPE to cm.handle(action, SoapCodec.parseArgs(bodyXml))
                else -> return fault(500, 401, "Invalid Action: unknown control path $controlPath")
            }
            logger("DLNA SOAP $controlPath#$action -> 200")
            200 to SoapCodec.successEnvelope(serviceType, action, outArgs)
        } catch (e: UpnpError) {
            logger("DLNA SOAP $controlPath#$action -> UPnP ${e.code}: ${e.message}")
            fault(500, e.code, e.message ?: "UPnPError")
        } catch (e: Exception) {
            logger("DLNA SOAP $controlPath#$action -> 501: ${e.message}")
            fault(500, 501, "Action Failed")
        }
    }

    private fun fault(httpCode: Int, upnpCode: Int, desc: String): Pair<Int, String> =
        httpCode to SoapCodec.faultEnvelope(upnpCode, desc)
}
