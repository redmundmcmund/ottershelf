package io.github.ottershelf.testing

import java.io.File

/**
 * Makes the zip [file]'s central directory say [entry] inflates to [size] bytes (below 4 GB),
 * whatever it really does, as a zip bomb can: `java.util.zip.ZipFile` reports it as the entry's
 * `size` and doesn't check it while inflating.
 */
fun declareSize(file: File, entry: String, size: Long) {
    require(size in 0..0xFFFF_FFFEL)
    val bytes = file.readBytes()
    val name = entry.toByteArray(Charsets.UTF_8)
    var found = false
    var i = 0
    while (i + 46 <= bytes.size) {
        // A central directory header: "PK\u0001\u0002"; its name length at 28, the name at 46.
        if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) {
            val nameLength = (bytes[i + 28].toInt() and 0xFF) or ((bytes[i + 29].toInt() and 0xFF) shl 8)
            if (nameLength == name.size && i + 46 + nameLength <= bytes.size && bytes.copyOfRange(i + 46, i + 46 + nameLength).contentEquals(name)) {
                for (b in 0 until 4) bytes[i + 24 + b] = (size shr (8 * b)).toByte() // uncompressed size, little-endian
                found = true
            }
        }
        i++
    }
    check(found) { "No central directory entry for $entry" }
    file.writeBytes(bytes)
}
