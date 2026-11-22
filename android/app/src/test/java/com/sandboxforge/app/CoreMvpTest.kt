package com.sandboxforge.app

import com.sandboxforge.app.clone.ApkManifestPatcher
import com.sandboxforge.app.clone.ApkZipAligner
import com.sandboxforge.app.clone.EngineAdvisor
import com.sandboxforge.app.clone.EngineMode
import com.sandboxforge.app.clone.LocalKeystoreManager
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
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
            installedAtMillis = 1700000000000L,
            recommendedEngine = EngineMode.MOD_SHIM,
            selectedEngine = EngineMode.CLEAN_PROFILE,
            clonePackageName = "sf.c1.com.example.demo",
            bridgeToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            shimVersion = "0.2.2"
        )
        storage.upsertApp(sample)
        val loaded = storage.loadApps()
        assertEquals(1, loaded.size)
        assertEquals(1, loaded.first().splitCount)
        assertEquals(EngineMode.CLEAN_PROFILE, loaded.first().selectedEngine)
        assertEquals("sf.c1.com.example.demo", loaded.first().clonePackageName)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            loaded.first().bridgeToken
        )
        assertEquals("0.2.2", loaded.first().shimVersion)

        storage.removeApp("com.example.demo")
        assertTrue(storage.loadApps().isEmpty())
    }

    @Test
    fun engineAdvisorDetectsBankingVsStandardApps() {
        val bankAdvice = EngineAdvisor.analyze("ru.sberbankmobile", emptyList(), listOf("arm64-v8a"))
        assertEquals(EngineMode.CLEAN_PROFILE, bankAdvice.recommendedMode)

        val gameAdvice = EngineAdvisor.analyze("com.example.arcade", emptyList(), listOf("arm64-v8a"))
        assertEquals(EngineMode.MOD_SHIM, gameAdvice.recommendedMode)
        val clonePkg = ApkManifestPatcher.buildClonePackageName("com.example.arcade")
        assertEquals("sf.c1.com.example.arcade", clonePkg)
        assertEquals("sf.c1.com.example.arcade.sandboxforge.files", ApkManifestPatcher.fileBridgeAuthority(clonePkg))
    }

    @Test
    fun zipAlignerAlignsResourcesTo4BytesAndNativeLibsTo16KB() {
        val srcZip = tempFolder.newFile("input.apk")
        val outZip = tempFolder.newFile("aligned.apk")

        ZipOutputStream(FileOutputStream(srcZip)).use { zos ->
            // Compressed entry + old signature to be stripped
            zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
            zos.write("old-sig".toByteArray())
            zos.closeEntry()

            // STORED resources.arsc
            val arscData = ByteArray(123) { 0x42 }
            val arscEntry = ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED
                size = arscData.size.toLong()
                compressedSize = arscData.size.toLong()
                crc = CRC32().apply { update(arscData) }.value
            }
            zos.putNextEntry(arscEntry)
            zos.write(arscData)
            zos.closeEntry()

            // STORED native .so library
            val soData = ByteArray(256) { 0x7F }
            val soEntry = ZipEntry("lib/arm64-v8a/libdemo.so").apply {
                method = ZipEntry.STORED
                size = soData.size.toLong()
                compressedSize = soData.size.toLong()
                crc = CRC32().apply { update(soData) }.value
            }
            zos.putNextEntry(soEntry)
            zos.write(soData)
            zos.closeEntry()
        }

        ApkZipAligner.repackAndAlign(
            sourceApk = srcZip,
            outputApk = outZip,
            additions = mapOf("assets/sandbox_config.json" to "{}".toByteArray())
        )

        val offsets = ApkZipAligner.inspectStoredDataOffsets(outZip)
        val arscOffset = offsets["resources.arsc"]
        val soOffset = offsets["lib/arm64-v8a/libdemo.so"]
        assertNotNull(arscOffset)
        assertNotNull(soOffset)
        assertEquals("resources.arsc must be 4-byte aligned", 0L, arscOffset!! % 4L)
        assertEquals("uncompressed .so must be 16KB aligned for Android 15-17", 0L, soOffset!! % 16384L)
    }

    @Test
    fun localKeystoreManagerGeneratesAndReloadsSignerConfig() {
        val baseDir = tempFolder.newFolder("ks_base")
        val mgr = LocalKeystoreManager(baseDir)

        val first = mgr.getSignerConfig()
        val second = mgr.getSignerConfig()
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(File(File(baseDir, "keys"), "sandboxforge_clone.p12").exists())
    }
}
