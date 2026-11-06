package com.beezync.sdk

data class BeezyncConfig(
    val appId: String,
    val publicKey: String,
    val baseUrl: String,
) {
    init {
        require(appId.isNotBlank()) { "appId is required" }
        require(publicKey.startsWith("pk_")) { "publicKey must be a public key (pk_...)" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "baseUrl must be http(s)" }
    }

    internal val httpBase: String get() = baseUrl.trimEnd('/')
}
