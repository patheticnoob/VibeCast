package dev.vibecast.tv.cast

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkAddressResolver {
    fun findLocalIpv4Address(): String? {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
