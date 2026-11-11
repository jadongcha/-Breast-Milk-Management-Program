package app.milkbank.ui

import android.util.Log

private const val TAG = "Milkbank"

/**
 * Developer-visible report for exceptions the UI turns into a generic message.
 * Logs only [what] and the exception (its type, message and stack) - never pass passwords, tokens or QR contents.
 */
fun logUnexpected(what: String): (Throwable) -> Unit = { e -> Log.w(TAG, what, e) }
