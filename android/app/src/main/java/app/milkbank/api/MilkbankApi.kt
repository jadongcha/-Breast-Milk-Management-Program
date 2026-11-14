package app.milkbank.api

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MilkbankApi {
    @GET("health") suspend fun health()

    @POST("auth/login") suspend fun login(@Body body: LoginRequest): LoginResponse
    /** [authorization] is explicit ("Bearer <token>"): the call is sent after the local session is gone. */
    @POST("auth/logout") suspend fun logout(@Header("Authorization") authorization: String)
    @POST("auth/password") suspend fun changePassword(@Body body: PasswordChangeRequest)
    @GET("auth/me") suspend fun me(): StaffDto

    @GET("patients")
    suspend fun patients(
        @Query("q") q: String?,
        @Query("include_discharged") includeDischarged: Boolean?,
    ): List<PatientDto>
    @GET("patients/{id}") suspend fun patient(@Path("id") id: Int): PatientDetailDto
    @POST("patients") suspend fun createPatient(@Body body: PatientForm): PatientDto
    @PATCH("patients/{id}")
    suspend fun updatePatient(@Path("id") id: Int, @Body body: PatientForm): PatientDto

    @POST("patients/{id}/records")
    suspend fun addRecord(@Path("id") patientId: Int, @Body body: RecordRequest): RecordResultDto
    @POST("records/{id}/cancel") suspend fun cancelRecord(@Path("id") recordId: Int): RecordResultDto

    @GET("staff") suspend fun staff(): List<StaffDto>
    @POST("staff") suspend fun createStaff(@Body body: StaffCreateRequest): StaffCreateResponse
    @PATCH("staff/{id}")
    suspend fun setActive(@Path("id") id: Int, @Body body: StaffPatchRequest): StaffDto
    @POST("staff/{id}/reset-password") suspend fun resetPassword(@Path("id") id: Int): TempPasswordResponse

    @GET("stations") suspend fun stations(): List<StationDto>
    @POST("stations") suspend fun createStation(@Body body: StationCreateRequest): StationCreateResponse
    @PATCH("stations/{id}")
    suspend fun patchStation(@Path("id") id: Int, @Body body: StationPatchRequest): StationDto
    @GET("station/self") suspend fun stationSelf(@Header("X-Station-Token") stationToken: String): StationSelfDto
    /** Staff Bearer is added by the interceptor; the station token is explicit. */
    @DELETE("station/self") suspend fun releaseStation(@Header("X-Station-Token") stationToken: String)

    @POST("guardian/login")
    suspend fun guardianLogin(
        @Header("X-Station-Token") stationToken: String?,
        @Body body: GuardianLoginRequest,
    ): GuardianLoginResponse
    @GET("guardian/babies")
    suspend fun guardianBabies(
        @Header("X-Station-Token") stationToken: String?,
        @Header("Authorization") authorization: String,
    ): List<BabyDto>
    @POST("guardian/submissions")
    suspend fun guardianSubmit(
        @Header("X-Station-Token") stationToken: String?,
        @Header("Authorization") authorization: String,
        @Body body: GuardianSubmitRequest,
    ): GuardianSubmitResponse
    @POST("guardian/logout")
    suspend fun guardianLogout(
        @Header("X-Station-Token") stationToken: String?,
        @Header("Authorization") authorization: String,
    )

    @GET("admin/overview") suspend fun overview(): OverviewDto
    @GET("admin/daily") suspend fun daily(@Query("date") date: String?): DailyDto
    @GET("admin/series")
    suspend fun series(@Query("days") days: Int, @Query("patient_id") patientId: Int?): List<SeriesPointDto>
    /** Raw bytes (UTF-8 BOM included) so the file reaches the user exactly as the server made it. */
    @GET("admin/export.csv") suspend fun exportCsv(@Query("from") from: String, @Query("to") to: String): ResponseBody
    @GET("admin/settings") suspend fun settings(): SettingsDto
    @PATCH("admin/settings") suspend fun saveSettings(@Body body: SettingsPatch): SettingsDto
    @POST("patients/{id}/discharge")
    suspend fun discharge(@Path("id") id: Int, @Body body: DischargeRequest): PatientDto
    @POST("patients/{id}/erase") suspend fun erase(@Path("id") id: Int): PatientDto

    @GET("submissions") suspend fun submissions(@Query("status") status: String): List<PendingDto>
    @POST("submissions/{id}/confirm")
    suspend fun confirmSubmission(@Path("id") id: Int, @Body body: ConfirmRequest): ConfirmResponse
    @POST("submissions/{id}/reject")
    suspend fun rejectSubmission(@Path("id") id: Int, @Body body: RejectRequest): RejectResponse
}
