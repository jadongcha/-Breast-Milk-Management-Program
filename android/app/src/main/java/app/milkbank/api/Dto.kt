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
    val ml: Int? = null,
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
    val ml: Int? = null,
)

@Serializable
data class RecordResultDto(val record: RecordDto, val balance: Int)

@Serializable
data class StationCreateRequest(val name: String)

@Serializable
data class StationPatchRequest(val active: Boolean)

@Serializable
data class StationDto(
    val id: Int,
    val name: String,
    val active: Boolean,
    @SerialName("created_at")
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class StationCreateResponse(
    val station: StationDto,
    @SerialName("station_token") val stationToken: String,
)

@Serializable
data class StationSelfDto(val id: Int, val name: String)

@Serializable
data class GuardianLoginRequest(val phone: String, @SerialName("baby_id") val babyId: String)

@Serializable
data class BabyDto(
    @SerialName("patient_id") val patientId: Int,
    @SerialName("baby_id") val babyId: String,
    val balance: Int,
    @SerialName("pending_count") val pendingCount: Int,
)

@Serializable
data class GuardianLoginResponse(val token: String, val babies: List<BabyDto>)

@Serializable
data class GuardianSubmitRequest(@SerialName("patient_id") val patientId: Int, val count: Int, val ml: Int? = null)

@Serializable
data class SubmissionBriefDto(
    val id: Int,
    val count: Int,
    @SerialName("submitted_at")
    @Serializable(with = InstantSerializer::class)
    val submittedAt: Instant,
    val ml: Int? = null,
)

@Serializable
data class GuardianSubmitResponse(
    val submission: SubmissionBriefDto,
    val balance: Int,
    @SerialName("pending_count") val pendingCount: Int,
)

@Serializable
data class PendingDto(
    val id: Int,
    @SerialName("patient_id") val patientId: Int,
    @SerialName("baby_id") val babyId: String,
    val balance: Int,
    @SerialName("station_name") val stationName: String,
    @SerialName("submitted_at")
    @Serializable(with = InstantSerializer::class)
    val submittedAt: Instant,
    val count: Int,
    val discharged: Boolean,
    val ml: Int? = null,
)

/** status is "pending", "confirmed" or "rejected". */
@Serializable
data class SubmissionDto(
    val id: Int,
    @SerialName("patient_id") val patientId: Int,
    val count: Int,
    @SerialName("submitted_at")
    @Serializable(with = InstantSerializer::class)
    val submittedAt: Instant,
    val status: String,
    @SerialName("confirmed_count") val confirmedCount: Int? = null,
    @SerialName("handled_by") val handledBy: Int? = null,
    @SerialName("handled_at")
    @Serializable(with = InstantSerializer::class)
    val handledAt: Instant? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    val memo: String? = null,
    val ml: Int? = null,
    @SerialName("confirmed_ml") val confirmedMl: Int? = null,
)

/** [ml] has no default on purpose: it is always sent (null clears it; omitting it keeps the guardian's value). */
@Serializable
data class ConfirmRequest(val count: Int, val ml: Int?)

@Serializable
data class ConfirmResponse(val record: RecordDto, val balance: Int, val submission: SubmissionDto)

@Serializable
data class RejectRequest(val reason: String, val memo: String? = null)

@Serializable
data class RejectResponse(val submission: SubmissionDto)
