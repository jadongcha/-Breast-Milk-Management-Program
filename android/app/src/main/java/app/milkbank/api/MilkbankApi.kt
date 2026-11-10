package app.milkbank.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MilkbankApi {
    @GET("health") suspend fun health()

    @POST("auth/login") suspend fun login(@Body body: LoginRequest): LoginResponse
    @POST("auth/logout") suspend fun logout()
    @POST("auth/password") suspend fun changePassword(@Body body: PasswordChangeRequest)
    @GET("auth/me") suspend fun me(): StaffDto

    @GET("patients") suspend fun patients(@Query("q") q: String?): List<PatientDto>
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
}
