package app.milkbank.server

enum class RegistrationDecision { SAVE, CONFIRM_DIFFERENT_SERVER }

fun decideRegistration(current: ServerConfig?, scanned: ServerConfig): RegistrationDecision =
    if (current == null || current.pin == scanned.pin) RegistrationDecision.SAVE
    else RegistrationDecision.CONFIRM_DIFFERENT_SERVER
