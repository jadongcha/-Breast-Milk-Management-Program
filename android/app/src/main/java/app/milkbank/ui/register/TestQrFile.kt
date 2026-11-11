package app.milkbank.ui.register

import java.io.File

/** Debug-only: the registration QR JSON placed at `<externalFilesDir>/qr.txt`. Null if missing or blank. */
fun readTestQr(dir: File?): String? {
    if (dir == null) return null
    return runCatching { File(dir, "qr.txt").readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
}
