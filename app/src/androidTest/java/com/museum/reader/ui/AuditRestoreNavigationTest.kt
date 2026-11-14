package com.museum.reader.ui

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AuditRestoreNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun restoringFromReaderSettingsReturnsToAValidHome() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = DocumentRepository(context)
        val doc = repo.create("恢复返回页", "恢复后仍能阅读。", DocumentSource.TXT)
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        lateinit var vm: MuseumReaderViewModel
        try {
            file.writeText(repo.exportBackup(ReaderSettings()))
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
            compose.waitUntil(8000) { !vm.state.value.isBusy }
            compose.runOnIdle { vm.openDocument(doc.id) }
            compose.waitUntil(8000) { vm.state.value.currentDocument?.id == doc.id && !vm.state.value.isBusy }
            compose.runOnIdle { vm.openSettings(); vm.importBackup(Uri.fromFile(file)) }
            compose.waitUntil(8000) { vm.state.value.pendingBackup != null }
            compose.onNodeWithText("确认恢复").performClick()
            compose.waitUntil(8000) { !vm.state.value.isBusy && vm.state.value.pendingBackup == null }
            if (vm.state.value.page == AppPage.SETTINGS) compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertEquals(AppPage.HOME, vm.state.value.page) }
            compose.waitUntil(8000) { vm.state.value.documents.any { it.id == doc.id } }
            assertEquals(doc.text, repo.find(doc.id)!!.text)
        } finally { file.delete(); ReaderDatabase.get(context).readerDao().purgeDocument(doc.id) }
    }
}
