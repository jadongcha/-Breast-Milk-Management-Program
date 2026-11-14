package app.milkbank.ui.patients

import app.milkbank.api.PatientDetailDto
import app.milkbank.api.PatientDto
import app.milkbank.api.PatientForm
import app.milkbank.api.RecordDto
import app.milkbank.api.RecordResultDto
import app.milkbank.core.ApiResult
import app.milkbank.data.PatientRepository
import app.milkbank.data.StockRepository
import java.time.Instant

fun patientDto(id: Int, regNo: String, balance: Int = 0) = PatientDto(
    id = id, regNo = regNo, babyName = "이름$id", bed = "$id", guardianName = "보호자", guardianPhone = "010",
    status = "admitted", admittedOn = "2026-11-01", alertEnabled = false, balance = balance,
)

fun recordDto(
    id: Int,
    kind: String = "in",
    count: Int = 3,
    staffId: Int = 1,
    createdAt: Instant = Instant.parse("2026-11-10T00:00:00Z"),
    canceled: Boolean = false,
    targetId: Int? = null,
) = RecordDto(
    id = id, kind = kind, count = count, targetId = targetId, staffId = staffId, staffName = "김간호",
    createdAt = createdAt, canceled = canceled,
)

fun detailDto(id: Int = 7, regNo: String = "DEMO-1", balance: Int = 5, records: List<RecordDto> = emptyList()) =
    PatientDetailDto(
        id = id, regNo = regNo, babyName = "비밀이름", bed = "3", guardianName = "보호자", guardianPhone = "010-1",
        status = "admitted", admittedOn = "2026-11-01", alertEnabled = false, balance = balance, records = records,
    )

class FakePatientRepository : PatientRepository {
    var listHandler: suspend (String?) -> ApiResult<List<PatientDto>> = { ApiResult.Ok(emptyList()) }
    var detailHandler: suspend (Int) -> ApiResult<PatientDetailDto> = { ApiResult.Ok(detailDto(it)) }
    var createHandler: suspend (PatientForm) -> ApiResult<PatientDto> = { ApiResult.Ok(patientDto(1, it.regNo)) }
    var updateHandler: suspend (Int, PatientForm) -> ApiResult<PatientDto> = { id, f -> ApiResult.Ok(patientDto(id, f.regNo)) }
    val createdForms = mutableListOf<PatientForm>()
    val updatedForms = mutableListOf<Pair<Int, PatientForm>>()
    var listCalls = 0
    var detailCalls = 0

    override suspend fun list(q: String?): ApiResult<List<PatientDto>> { listCalls++; return listHandler(q) }
    override suspend fun detail(id: Int): ApiResult<PatientDetailDto> { detailCalls++; return detailHandler(id) }
    override suspend fun create(form: PatientForm): ApiResult<PatientDto> { createdForms += form; return createHandler(form) }
    override suspend fun update(id: Int, form: PatientForm): ApiResult<PatientDto> { updatedForms += id to form; return updateHandler(id, form) }
}

class FakeStockRepository : StockRepository {
    var addHandler: suspend (Int, String, Int, String?, String?) -> ApiResult<RecordResultDto> =
        { _, _, c, _, _ -> ApiResult.Ok(RecordResultDto(recordDto(100, count = c), 8)) }
    var cancelHandler: suspend (Int) -> ApiResult<RecordResultDto> =
        { id -> ApiResult.Ok(RecordResultDto(recordDto(101, kind = "cancel", targetId = id), 2)) }
    var addCalls = 0
    val addMls = mutableListOf<Int?>()
    var cancelCalls = 0

    override suspend fun add(patientId: Int, kind: String, count: Int, reason: String?, memo: String?, ml: Int?): ApiResult<RecordResultDto> {
        addCalls++
        addMls += ml
        return addHandler(patientId, kind, count, reason, memo)
    }

    override suspend fun cancel(recordId: Int): ApiResult<RecordResultDto> {
        cancelCalls++
        return cancelHandler(recordId)
    }
}
