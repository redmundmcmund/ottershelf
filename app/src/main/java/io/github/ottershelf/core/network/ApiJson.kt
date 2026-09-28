package io.github.ottershelf.core.network

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for everything the server sends and takes, and for what the data layer
 * stores on the device (progress records, download metadata). Unknown fields are ignored on decode;
 * the server rejects unknown fields on requests, so request models carry only what its DTOs allow.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true // provider ids arrive as strings or numbers depending on the provider
}
