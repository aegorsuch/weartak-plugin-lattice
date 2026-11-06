// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice.core

import java.net.URI

class ConnectionConfig(endpoint: String, val token: String, val sandboxToken: String = "") {
    val origin: URI = URI(endpoint.also { require(it.length in 1..2048) }).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null &&
            it.rawPath in listOf("", "/") && (it.port == -1 || it.port in 1..65535)) {
            "Enter an HTTPS origin without a path, credentials, query, or fragment."
        }
    }
    init {
        require(token.length in 1..8192 && validToken(token) &&
            sandboxToken.length <= 8192 && (sandboxToken.isEmpty() || validToken(sandboxToken))) {
            "Enter raw bearer tokens without spaces or control characters."
        }
    }
    val streamUrl: String get() = origin.resolve("/api/v1/entities/stream").toASCIIString()

    // Deliberately not a data class: generated toString must never expose credentials.
    override fun toString() = "ConnectionConfig(redacted)"

    private fun validToken(value: String) = value.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~+/="
    }
}
