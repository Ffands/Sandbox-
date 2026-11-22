package com.sandboxforge.app

import com.sandboxforge.app.container.ContainerApp
import com.sandboxforge.app.container.ContainerFileManager
import com.sandboxforge.app.container.ContainerStorage
import com.sandboxforge.app.container.InstallSource
import com.sandboxforge.app.logging.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreMvpTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun appLoggerWritesSessionAndCrashDumpToDisk() {
        AppLogger.resetForTest()
        val baseDir = tempFolder.newFolder("app_files")
        AppLogger.init(baseDir)

        AppLogger.i("TestTag", "Событие тестовой сессии")
        val sessionFile = AppLogger.getCurrentSessionFile()
        assertNotNull(sessionFile)
        assertTrue(sessionFile!!.exists())
        assertTrue(sessionFile.readText().contains("Событие тестовой сессии"))

        AppLogger.recordCrashSync("main", IllegalStateException("Simulated crash"))
        val crashFile = File(File(baseDir, "logs"), "last_crash.log")
        assertTrue(crashFile.exists())
        assertTrue(crashFile.readText().contains("Simulated crash"))
        assertTrue(AppLogger.exportAllText().contains("ПОСЛЕДНИЙ КРАШ-ДАМП"))
    }

    @Test
    fun fileManagerBlocksTraversalOutsideContainerRoot() {
        val root = tempFolder.newFolder("container_root")
        val outside = tempFolder.newFile("secret_outside.txt").apply { writeText("outside") }
        val fm = ContainerFileManager(root)

        assertFalse(fm.isInsideContainer(outside))
        assertTrue(fm.listDirectory(outside.parentFile!!).isFailure)
        assertTrue(fm.createFolder(root, "../escape").isFailure)

        val created = fm.createFolder(root, "saves").getOrThrow()
        assertTrue(created.exists() && created.isDirectory)
        assertEquals("/saves", fm.relativePath(created))
    }

    @Test
    fun containerStoragePersistsAndRemovesApps() {
        val baseDir = tempFolder.newFolder("storage_base")
        val storage = ContainerStorage(baseDir)

        val sample = ContainerApp(
            packageName = "com.example.demo",
            appName = "Demo App",
            versionName = "1.2.3",
            versionCode = 12L,
            source = InstallSource.IMPORTED_FROM_DEVICE,
            apkFileNames = listOf("base.apk", "split_config.arm64_v8a.apk"),
            totalApkBytes = 1024L,
            nativeAbis = listOf("arm64-v8a"),
            installedAtMillis = 1700000000000L
        )
        storage.upsertApp(sample)
        val loaded = storage.loadApps()
        assertEquals(1, loaded.size)
        assertEquals(1, loaded.first().splitCount)

        storage.removeApp("com.example.demo")
        assertTrue(storage.loadApps().isEmpty())
    }
}
