package app.milkbank

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.milkbank.server.ServerConfig
import app.milkbank.session.LogoutReason
import app.milkbank.station.StationConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GuardianFlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val container get() = (ApplicationProvider.getApplicationContext<Application>() as MilkbankApp).container

    // What the installed app had stored before the test; restored exactly in tearDown.
    private var configRead = false
    private var previousConfig: ServerConfig? = null
    private var previousStation: StationConfig? = null
    private var overrideApplied = false

    @Before
    fun setUp() {
        runBlocking {
            previousConfig = container.serverConfigStore.config.first()
            previousStation = container.stationStore.config.first()
        }
        configRead = true
        container.overrideForTest(fakeRepositories(container.session, FakeWorld()))
        overrideApplied = true
        runBlocking {
            container.serverConfigStore.save(ServerConfig("https://fake.invalid:8443", "fake-pin"))
            // Real Keystore cipher on the device; the token is a fake value and is never sent anywhere.
            container.stationStore.save(StationConfig("fake-station-token", FAKE_STATION_NAME))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            try {
                container.session.end(LogoutReason.USER)
            } finally {
                try {
                    if (configRead) {
                        runBlocking {
                            val st = previousStation
                            if (st != null) container.stationStore.save(st) else container.stationStore.clear()
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

    private fun assertNameNotShown() {
        val hits = rule.onAllNodes(hasText(FAKE_BABY_NAME, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size +
            rule.onAllNodes(hasContentDescription(FAKE_BABY_NAME, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().size
        check(hits == 0) { "baby name is shown on screen" }
    }

    private val inDialog = hasAnyAncestor(isDialog()) and hasClickAction()

    @Test
    fun guardianFlow_submitShowsDone() {
        waitForText("제출처 태블릿 · $FAKE_STATION_NAME", timeoutMillis = 20_000)
        rule.onNodeWithText("보호자 전화번호").performTextInput(FAKE_GUARDIAN_PHONE)
        rule.onNodeWithText("아기 ID").performTextInput(FAKE_REG_NO)
        rule.onNodeWithText("확인").performClick()

        waitForText("현재 확인된 냉동 모유")
        assertNameNotShown()
        rule.onNodeWithText("가져온 모유 입력").performClick()

        waitForText("가져온 모유 팩 수를 눌러 주세요")
        rule.onNodeWithText("3").performClick()

        waitForText("$FAKE_REG_NO 아기 모유 3팩을 제출합니다")
        assertNameNotShown()
        rule.onNode(hasText("제출") and inDialog).performClick()

        waitForText("제출 접수 완료")
        assertNameNotShown()
    }
}
