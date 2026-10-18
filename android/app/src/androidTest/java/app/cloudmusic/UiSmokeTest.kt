package app.cloudmusic

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.data.Provider
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun removeTestCollection() {
        val app = compose.activity.application as CloudMusicApplication
        runBlocking { app.repository.library.value.folders.filter { it.provider == Provider.DEMO }.forEach { app.repository.remove(it) } }
    }
    @Test fun librarySearchPlayerThemesAndQueue() {
        val app = compose.activity.application as CloudMusicApplication
        runBlocking { app.repository.load(); app.repository.library.value.folders.filter { it.provider == Provider.DEMO }.forEach { app.repository.remove(it) }; app.repository.demo() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Моя музыка").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription("Включить По орбите — Cloud Music"))
        compose.onNodeWithContentDescription("Включить По орбите — Cloud Music").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Открыть плеер").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Открыть плеер").performClick()
        compose.onNodeWithContentDescription("Свернуть плеер").assertExists()
        compose.onNodeWithContentDescription("Добавить в избранное").performClick()
        compose.onNodeWithContentDescription("Очередь воспроизведения").performClick()
        compose.onNodeWithText("Очередь воспроизведения").assertIsDisplayed()
        val back = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(back).use { it.readBytes() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Свернуть плеер").performClick()
        compose.onNodeWithText("Поиск", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("орбите")
        compose.onNodeWithContentDescription("Включить По орбите — Cloud Music").assertExists()
        compose.onNodeWithContentDescription("Включить Тихое утро — Cloud Music").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Светлая", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Включить тёмную тему").assertExists()
        compose.onNodeWithText("Тёмная", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Включить светлую тему").assertExists()
    }

    @Test fun connectedLibraryAndRealPlayerScreens() {
        val app = compose.activity.application as CloudMusicApplication
        runBlocking {
            app.repository.load()
            app.repository.library.value.folders.filter { it.provider == Provider.DEMO }.forEach { app.repository.remove(it) }
        }
        val tracks = app.repository.library.value.tracks
        assumeTrue("A real folder is required for this UI test", tracks.isNotEmpty())
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Моя музыка").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithContentDescription("Включить тёмную тему").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Включить тёмную тему").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Обложка", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size >= 3 }
        screenshot("library-dark.png")
        compose.onNodeWithContentDescription("Включить светлую тему").performClick()
        screenshot("library-light.png")
        val track = tracks.first()
        val playLabel = "Включить ${track.title} — ${track.artist}"
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription(playLabel))
        compose.onNodeWithContentDescription(playLabel).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Пауза").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Открыть плеер").performClick()
        screenshot("player-light.png")
        compose.onNodeWithContentDescription("Свернуть плеер").performClick()
        compose.onNodeWithContentDescription("Включить тёмную тему").performClick()
        compose.onNodeWithContentDescription("Открыть плеер").performClick()
        screenshot("player-dark.png")
        compose.onNodeWithContentDescription("Очередь воспроизведения").performClick()
        screenshot("queue-dark.png")
        val back = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(back).use { it.readBytes() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Свернуть плеер").performClick()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Светлая", useUnmergedTree = true).performClick()
        screenshot("settings-light.png")
        compose.onNodeWithText("Подключённые папки", useUnmergedTree = true).performClick()
        screenshot("folders-light.png")
        compose.onNodeWithText("Добавить папку", useUnmergedTree = true).performClick()
        screenshot("connect-light.png")
        compose.onNodeWithContentDescription("Включить тёмную тему").performClick()
        screenshot("connect-dark.png")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150)
        val app = compose.activity.application
        val directory = java.io.File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("Native screenshot must be available", bitmap)
        java.io.FileOutputStream(java.io.File(directory, name)).use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap?.recycle()
    }
}
