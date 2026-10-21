package app.musicplayer.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.musicplayer.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MolpyUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val permission: TestRule =
        if (Build.VERSION.SDK_INT >= 33)
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else TestRule { base, _ -> base }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(compose)

    @Test
    fun navigationKeepsDownloadsInLibraryAndSettingsInProfile() {
        compose.onNodeWithText("Molpy · технический экран").assertDoesNotExist()
        compose.onNodeWithText("Избранное").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Загрузки").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Избранное").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Поиск").performClick()
        compose.onNodeWithText("Название или исполнитель").assertIsDisplayed()
        compose.onNodeWithText("Профиль").performClick()
        compose.onNodeWithText("Время прослушивания").assertIsDisplayed()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Системное подключение / VPN").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Время прослушивания").assertIsDisplayed()
    }
}
