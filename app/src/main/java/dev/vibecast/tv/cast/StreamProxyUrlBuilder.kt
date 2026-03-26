package dev.vibecast.tv.cast

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun buildLocalProxyUrl(baseUrl: String, proxyId: String, upstreamUrl: String, rootOrigin: String? = null): String {
    val upstreamUri = URI(upstreamUrl)
    val pathWithQuery = upstreamUri.rawPath.orEmpty().ifBlank { "/" } +
        upstreamUri.rawQuery?.let { "?$it" }.orEmpty()
    val normalizedRootOrigin = rootOrigin?.let(::originOf)
    val upstreamOrigin = originOf(upstreamUrl)

    return if (normalizedRootOrigin != null && normalizedRootOrigin == upstreamOrigin) {
        "$baseUrl/proxy/$proxyId$pathWithQuery"
    } else {
        val encodedUrl = URLEncoder.encode(upstreamUrl, StandardCharsets.UTF_8)
        "$baseUrl/proxy/$proxyId?url=$encodedUrl"
    }
}

fun resolveProxyTargetUrl(
    proxyRootUrl: String,
    requestUri: String,
    rawQuery: String?,
    explicitUpstreamUrl: String?,
): String {
    if (!explicitUpstreamUrl.isNullOrBlank()) {
        return explicitUpstreamUrl
    }

    val proxyRoot = URI(proxyRootUrl)
    val proxyId = requestUri.substringAfter("/proxy/").substringBefore('/')
    val suffix = requestUri.substringAfter("/proxy/$proxyId", "")

    return if (suffix.isBlank()) {
        proxyRootUrl
    } else {
        val baseOrigin = "${proxyRoot.scheme}://${proxyRoot.authority}"
        val query = rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        "$baseOrigin$suffix$query"
    }
}

fun originOf(url: String): String {
    val uri = URI(url)
    return "${uri.scheme}://${uri.authority}"
}
