package app.milkbank.ui.station

import app.milkbank.api.StationCreateResponse
import app.milkbank.api.StationDto
import app.milkbank.api.StationSelfDto
import app.milkbank.core.ApiResult
import app.milkbank.data.StationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StationsViewModelTest {
    private class Repo : StationRepository {
        val items = mutableListOf(
            StationDto(1, "A", true, Instant.EPOCH),
            StationDto(2, "B", true, Instant.EPOCH),
        )
        val deactivated = mutableListOf<Int>()
        override suspend fun create(name: String): ApiResult<StationCreateResponse> = error("unused")
        override suspend fun list(): ApiResult<List<StationDto>> = ApiResult.Ok(items.toList())
        override suspend fun deactivate(id: Int): ApiResult<StationDto> {
            deactivated += id
            val i = items.indexOfFirst { it.id == id }
            items[i] = items[i].copy(active = false)
            return ApiResult.Ok(items[i])
        }
        override suspend fun self(stationToken: String): ApiResult<StationSelfDto> = error("unused")
        override suspend fun releaseSelf(stationToken: String): ApiResult<Unit> = error("unused")
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun stations_deactivate_refreshesList() = runTest {
        val repo = Repo()
        val vm = StationsViewModel({ repo }, "서버에 연결할 수 없습니다")
        vm.refresh(); advanceUntilIdle()
        assertEquals(listOf(true, true), vm.items.value.map { it.active })
        vm.deactivate(2); advanceUntilIdle()
        assertEquals(listOf(2), repo.deactivated)
        assertEquals(listOf(true, false), vm.items.value.map { it.active })
    }
}
