package app.milkbank

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Admin menu: overview and daily usage screens, against fake repositories. */
class AdminFlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val container get() = (ApplicationProvider.getApplicationContext<Application>() as MilkbankApp).container

    // What the installed app had stored before the test; restored exactly in tearDown.
    private var configRead = false
    private var previousConfig: ServerConfig? = null
    private var overrideApplied = false

    @Before
    fun setUp() {
        grantLocalNetworkPermission()
        runBlocking { previousConfig = container.serverConfigStore.config.first() }
        configRead = true
        container.overrideForTest(fakeRepositories(container.session, FakeWorld()))
        overrideApplied = true
        // The start destination needs a registered server; it is never contacted (fakes).
        runBlocking { container.serverConfigStore.save(ServerConfig("https://fake.invalid:8443", "fake-pin")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            try {
                container.session.end(LogoutReason.USER) // also clears the result banner
            } finally {
                try {
                    if (configRead) {
                        runBlocking {
                            val prev = previousConfig
                            if (prev != null) container.serverConfigStore.save(prev) else container.serverConfigStore.clear()
                        }
                    }
                } finally {
                    if (overrideApplied) container.clearTestOverride()
                }
            }
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openMenuItem(label: String) {
        rule.onNodeWithContentDescription("더보기").performClick()
        waitForText(label)
        rule.onNodeWithText(label).performClick()
    }

    @Test
    fun admin_overviewThenDaily() {
        waitForText("간호사·직원", timeoutMillis = 20_000)
        rule.onNodeWithText("간호사·직원").performClick()
        waitForText("로그인")
        rule.onNodeWithText("아이디").performTextInput(FAKE_ADMIN_USERNAME)
        rule.onNodeWithText("비밀번호").performTextInput(FAKE_ADMIN_PASSWORD)
        rule.onNodeWithText("로그인").performClick()

        waitForText("아기 ID 검색", timeoutMillis = 20_000)
        openMenuItem("전체 현황")
        waitForText("부족 1명")
        waitForText(FAKE_REG_NO)

        rule.onNodeWithText("← 뒤로").performClick()
        waitForText("아기 ID 검색")

        openMenuItem("일별 사용량")
        waitForText("mL(입력분)")
        waitForText("합계")
        waitForText("2팩 · 240mL")
    }
}
