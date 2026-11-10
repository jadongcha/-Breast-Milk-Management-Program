package app.milkbank.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Accepts a trailing Z, a +00:00 offset, or no offset at all (treated as UTC). */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = parseInstant(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    fun parseInstant(s: String): Instant =
        try {
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: Exception) {
                LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
            }
        }
}

@Serializable
data class StaffDto(
    val id: Int,
    val username: String,
    val name: String,
    val role: String,
    val active: Boolean,
    @SerialName("must_change_password") val mustChangePassword: Boolean,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("must_change_password") val mustChangePassword: Boolean,
    val staff: StaffDto,
)

@Serializable
data class PasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class StaffCreateRequest(val username: String, val name: String, val role: String)

@Serializable
data class StaffCreateResponse(
    val staff: StaffDto,
    @SerialName("temp_password") val tempPassword: String,
)

@Serializable
data class StaffPatchRequest(val active: Boolean)

@Serializable
data class TempPasswordResponse(@SerialName("temp_password") val tempPassword: String)

/**
 * Body for both POST /patients and PATCH /patients/{id}. Dates are "YYYY-MM-DD".
 * No defaults on purpose: every field must be encoded (the server PATCH uses exclude_unset,
 * so an omitted field means "unchanged"). On create, admittedOn = null means server default (today).
 */
@Serializable
data class PatientForm(
    @SerialName("reg_no") val regNo: String,
    @SerialName("baby_name") val babyName: String,
    val bed: String,
    @SerialName("guardian_name") val guardianName: String,
    @SerialName("guardian_phone") val guardianPhone: String,
    @SerialName("admitted_on") val admittedOn: String?,
    @SerialName("alert_enabled") val alertEnabled: Boolean,
    @SerialName("days_threshold") val daysThreshold: Int?,
    @SerialName("packs_threshold") val packsThreshold: Int?,
)

@Serializable
data class PatientDto(
    val id: Int,
    @SerialName("reg_no") val regNo: String,
    @SerialName("baby_name") val babyName: String,
    val bed: String,
    @SerialName("guardian_name") val guardianName: String,
    @SerialName("guardian_phone") val guardianPhone: String,
    val status: String,
    @SerialName("admitted_on") val admittedOn: String,
    @SerialName("discharged_on") val dischargedOn: String? = null,
    @SerialName("alert_enabled") val alertEnabled: Boolean,
    @SerialName("days_threshold") val daysThreshold: Int? = null,
    @SerialName("packs_threshold") val packsThreshold: Int? = null,
    val balance: Int,
)

@Serializable
data class RecordDto(
    val id: Int,
    val kind: String,
    val count: Int,
    @SerialName("discard_reason") val discardReason: String? = null,
    @SerialName("target_id") val targetId: Int? = null,
    val memo: String? = null,
    @SerialName("staff_id") val staffId: Int,
    @SerialName("staff_name") val staffName: String,
    @SerialName("created_at")
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    val canceled: Boolean,
)

@Serializable
data class PatientDetailDto(
    val id: Int,
    @SerialName("reg_no") val regNo: String,
    @SerialName("baby_name") val babyName: String,
    val bed: String,
    @SerialName("guardian_name") val guardianName: String,
    @SerialName("guardian_phone") val guardianPhone: String,
    val status: String,
    @SerialName("admitted_on") val admittedOn: String,
    @SerialName("discharged_on") val dischargedOn: String? = null,
    @SerialName("alert_enabled") val alertEnabled: Boolean,
    @SerialName("days_threshold") val daysThreshold: Int? = null,
    @SerialName("packs_threshold") val packsThreshold: Int? = null,
    val balance: Int,
    val records: List<RecordDto>,
)

/** kind is "intake", "use" or "discard". */
@Serializable
data class RecordRequest(
    val kind: String,
    val count: Int,
    @SerialName("discard_reason") val discardReason: String? = null,
    val memo: String? = null,
)

@Serializable
data class RecordResultDto(val record: RecordDto, val balance: Int)
