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
import kotlinx.coroutines.flow.first
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

    /** The baby's name must not appear as text or content description anywhere (merged or unmerged tree). */
    private fun assertNameNotShown() {
        val name = FAKE_BABY_NAME
        val textHit = hasText(name, substring = true)
        val descHit = hasContentDescription(name, substring = true)
        rule.onAllNodes(textHit, useUnmergedTree = true).assertCountIs(0)
        rule.onAllNodes(descHit, useUnmergedTree = true).assertCountIs(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIs(n: Int) {
        val found = fetchSemanticsNodes().size
        check(found == n) { "expected $n nodes but found $found" }
    }

    private val inDialog = hasAnyAncestor(isDialog()) and hasClickAction()

    private fun dialogButton(text: String) =
        rule.onNode(hasText(text) and inDialog)

    @Test
    fun mainFlow_intakeUpdatesBalance() {
        // Login (cold start can be slow)
        waitForText("로그인", timeoutMillis = 20_000)
        rule.onNodeWithText("아이디").performTextInput(FAKE_USERNAME)
        rule.onNodeWithText("비밀번호").performTextInput(FAKE_PASSWORD)
        rule.onNodeWithText("로그인").performClick()

        // Home: search by baby ID and select
        waitForText("아기 ID 검색")
        rule.onNodeWithText("아기 ID 검색").performTextInput(FAKE_REG_NO)
        waitForText("$FAKE_REG_NO · 선택")
        assertNameNotShown()
        rule.onNodeWithText("$FAKE_REG_NO · 선택").performClick()

        // Detail
        waitForText("$FAKE_REG_NO 모유 재고 상세")
        assertNameNotShown()

        // Intake dialog: pick 3, press the dialog's 입고 button
        rule.onNodeWithText("입고").performClick()
        waitForText("팩 수")
        dialogButton("3").performClick()
        dialogButton("입고").performClick()

        // Confirmation dialog
        waitForText("$FAKE_REG_NO 아기에게 3팩 입고하시겠습니까?")
        assertNameNotShown()
        dialogButton("확인").performClick()

        // The saved message may be a transient snackbar: poll for it right after confirming.
        waitForText("저장했습니다")
        // Persistent signals: balance and the new record row
        waitForText("3팩")
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("입고 3팩", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Back home: result banner
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("$FAKE_REG_NO · 3팩 입고 완료 · 현재 냉동 재고 3팩")
        assertNameNotShown()
    }
}
