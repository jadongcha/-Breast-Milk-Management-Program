package app.milkbank.ui.patients

import app.milkbank.api.PatientDto

sealed interface BabyMatch {
    data object Empty : BabyMatch
    data object None : BabyMatch
    data class Single(val patient: PatientDto) : BabyMatch
    data class Many(val patients: List<PatientDto>) : BabyMatch
}

/** Matches the baby ID (reg_no) only; names are never searched. Exact match wins over partial. */
fun matchBabies(query: String, patients: List<PatientDto>): BabyMatch {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return BabyMatch.Empty
    patients.firstOrNull { it.regNo.trim().lowercase() == q }?.let { return BabyMatch.Single(it) }
    val partial = patients.filter { it.regNo.lowercase().contains(q) }
    return when (partial.size) {
        0 -> BabyMatch.None
        1 -> BabyMatch.Single(partial[0])
        else -> BabyMatch.Many(partial)
    }
}
