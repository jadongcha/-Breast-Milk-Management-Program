package app.milkbank.ui.patients

import app.milkbank.core.ApiError
import app.milkbank.core.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatientListViewModelTest {
    private val repo = FakePatientRepository()
    private val banner = ResultBannerStore()
    private val list = listOf(patientDto(1, "DEMO-1", 4), patientDto(2, "DEMO-10", 0))

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repo.listHandler = { ApiResult.Ok(list) }
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PatientListViewModel({ repo }, banner, debounceMs = 250)

    @Test fun refresh_loadsPatients() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        assertEquals(list, v.patients.value)
        assertNull(v.error.value)
        assertFalse(v.loading.value)
    }

    @Test fun refresh_error_isShown_andKeepsOldList() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        repo.listHandler = { ApiResult.Err(ApiError(null, "서버에 연결할 수 없습니다")) }
        v.refresh(); advanceUntilIdle()
        assertEquals("서버에 연결할 수 없습니다", v.error.value)
        assertEquals(list, v.patients.value)
    }

    @Test fun listFiltering_isDebounced() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.onQueryChange("demo")
        advanceTimeBy(200)
        assertEquals(BabyMatch.Empty, v.listMatch.value)
        advanceTimeBy(100); advanceUntilIdle()
        assertEquals(BabyMatch.Many(list), v.listMatch.value)
    }

    @Test fun selectTarget_correspondsToCurrentQueryImmediately() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.onQueryChange("DEMO-1")
        assertEquals(BabyMatch.Single(list[0]), v.match.value)
        v.onQueryChange("DEMO-10")
        assertEquals(BabyMatch.Single(list[1]), v.match.value)
        v.onQueryChange("DEMO-1x")
        assertEquals(BabyMatch.None, v.match.value) // no stale Single for an older query
    }

    @Test fun search_onlyLastQueryApplies() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.onQueryChange("demo"); advanceTimeBy(100)
        v.onQueryChange("demo-10"); advanceUntilIdle()
        assertEquals(BabyMatch.Single(list[1]), v.match.value)
        assertEquals(BabyMatch.Single(list[1]), v.listMatch.value)
    }

    @Test fun clearingQuery_resetsAtOnce() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        v.onQueryChange("demo"); advanceUntilIdle()
        v.onQueryChange("")
        assertEquals(BabyMatch.Empty, v.match.value)
    }

    @Test fun savedResult_showsBannerAndRefreshes() = runTest {
        val v = vm(); v.refresh(); advanceUntilIdle()
        val before = repo.listCalls
        banner.post(ResultBanner("DEMO-1", "intake", 3, 8)); advanceUntilIdle()
        assertEquals(ResultBanner("DEMO-1", "intake", 3, 8), v.lastResult.value)
        assertEquals(before + 1, repo.listCalls)
    }

    @Test fun expanded_toggles() = runTest {
        val v = vm()
        assertFalse(v.expanded.value)
        v.toggleExpanded()
        assertTrue(v.expanded.value)
    }
}
