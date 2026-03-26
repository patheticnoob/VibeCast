package dev.vibecast.tv.cast

data class StreamProxyTarget(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val rootOrigin: String? = null,
)
