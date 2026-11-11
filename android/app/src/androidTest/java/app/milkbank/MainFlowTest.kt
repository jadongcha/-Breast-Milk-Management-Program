package app.milkbank

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.milkbank.server.ServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainFlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val container get() = (ApplicationProvider.getApplicationContext<Application>() as MilkbankApp).container

    @Before
    fun setUp() {
        container.overrideForTest(fakeRepositories(container.session, FakeWorld()))
        // The start destination needs a registered server; it is never contacted (fakes).
        runBlocking { container.serverConfigStore.save(ServerConfig("https://fake.invalid:8443", "fake-pin")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { container.serverConfigStore.clear() }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun mainFlow_intakeUpdatesBalance() {
        // Login
        waitForText("로그인")
        rule.onNodeWithText("아이디").performTextInput(FAKE_USERNAME)
        rule.onNodeWithText("비밀번호").performTextInput(FAKE_PASSWORD)
        rule.onNodeWithText("로그인").performClick()

        // Home: search by baby ID and select
        waitForText("아기 ID 검색")
        rule.onNodeWithText("아기 ID 검색").performTextInput(FAKE_REG_NO)
        waitForText("$FAKE_REG_NO · 선택")
        rule.onNodeWithText("$FAKE_REG_NO · 선택").performClick()

        // Detail: intake 3
        waitForText("$FAKE_REG_NO 모유 재고 상세")
        rule.onNodeWithText("입고").performClick()
        waitForText("팩 수")
        rule.onNodeWithText("3").performClick()
        // Dialog title and primary button both read "입고"; the button comes last.
        val intakeNodes = rule.onAllNodesWithText("입고")
        intakeNodes[intakeNodes.fetchSemanticsNodes().size - 1].performClick()

        // Confirmation dialog
        waitForText("$FAKE_REG_NO 아기에게 3팩 입고하시겠습니까?")
        rule.onNodeWithText("확인").performClick()

        // Balance and saved message on the detail screen
        waitForText("저장했습니다")
        waitForText("3팩")

        // Back home: result banner
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("$FAKE_REG_NO · 3팩 입고 완료 · 현재 냉동 재고 3팩")

        // The baby's name is never shown
        rule.onAllNodes(hasText(FAKE_BABY_NAME, substring = true)).assertCountEquals(0)
    }
}
