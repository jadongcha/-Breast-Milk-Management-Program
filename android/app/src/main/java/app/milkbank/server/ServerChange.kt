package app.milkbank.server

/** True when a previously registered server is replaced by a different one (or cleared). */
fun shouldResetSession(old: ServerConfig?, new: ServerConfig?): Boolean = old != null && old != new
