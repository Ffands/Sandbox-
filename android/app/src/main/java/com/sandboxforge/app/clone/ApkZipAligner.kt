package com.sandboxforge.app.clone

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rebuilds an APK ZIP archive while:
 * 1. Stripping old META-INF signature entries so apksig produces a clean v1/v2/v3 signature
 * 2. Preserving uncompressed STORED entries and aligning:
 *    - resources.arsc -> 4-byte aligned & STORED (required on Android 11+ / API 30+)
 *    - uncompressed .so native libs -> 16384-byte (16 KB) aligned (satisfies both 4 KB and 16 KB Android 15–17!)
 *    - other STORED entries -> 4-byte aligned
 * 3. Replacing or injecting custom entries (patched AndroidManifest.xml, sandbox_shim.dex, sandbox_config.json)
 */
object ApkZipAligner {
    const val ALIGN_DEFAULT = 4
    const val ALIGN_NATIVE_LIB = 16384 // 16 KB — multiple of both 4096 and 16384

    fun repackAndAlign(
        sourceApk: File,
        outputApk: File,
        replacements: Map<String, ByteArray> = emptyMap(),
        additions: Map<String, ByteArray> = emptyMap()
    ) {
        val writtenNames = hashSetOf<String>()

        ZipFile(sourceApk).use { zip ->
            FileOutputStream(outputApk).use { fos ->
                val counting = CountingOutputStream(BufferedOutputStream(fos, 64 * 1024))
                ZipOutputStream(counting).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val orig = entries.nextElement()
                        val name = orig.name

                        if (isSignatureEntry(name)) continue
                        if (orig.isDirectory) continue

                        writtenNames.add(name)

                        val replacementBytes = replacements[name]
                        if (replacementBytes != null) {
                            val storeUncompressed = (name == "resources.arsc")
                            writeBytesEntry(
                                zos = zos,
                                counting = counting,
                                name = name,
                                data = replacementBytes,
                                stored = storeUncompressed,
                                alignment = if (storeUncompressed) ALIGN_DEFAULT else 1
                            )
                        } else {
                            val mustStore = orig.method == ZipEntry.STORED || name == "resources.arsc"
                            if (mustStore) {
                                val raw = zip.getInputStream(orig).use { it.readBytes() }
                                val align = when {
                                    name.endsWith(".so") -> ALIGN_NATIVE_LIB
                                    else -> ALIGN_DEFAULT
                                }
                                writeBytesEntry(
                                    zos = zos,
                                    counting = counting,
                                    name = name,
                                    data = raw,
                                    stored = true,
                                    alignment = align
                                )
                            } else {
                                val outEntry = ZipEntry(name).apply {
                                    method = ZipEntry.DEFLATED
                                }
                                zos.putNextEntry(outEntry)
                                zip.getInputStream(orig).use { input ->
                                    input.copyTo(zos, bufferSize = 64 * 1024)
                                }
                                zos.closeEntry()
                                zos.flush()
                            }
                        }
                    }

                    // Write newly injected entries (e.g. classesN.dex, assets/sandbox_config.json)
                    additions.forEach { (name, data) ->
                        if (writtenNames.add(name)) {
                            writeBytesEntry(
                                zos = zos,
                                counting = counting,
                                name = name,
                                data = data,
                                stored = false,
                                alignment = 1
                            )
                        }
                    }
                }
            }
        }
    }

    private fun writeBytesEntry(
        zos: ZipOutputStream,
        counting: CountingOutputStream,
        name: String,
        data: ByteArray,
        stored: Boolean,
        alignment: Int
    ) {
        val entry = ZipEntry(name)
        if (stored) {
            zos.flush()
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val headerStart = counting.bytesWritten
            val unpaddedDataOffset = headerStart + 30L + nameBytes.size.toLong()
            val pad = if (alignment > 1) {
                val rem = (unpaddedDataOffset % alignment).toInt()
                if (rem == 0) 0 else alignment - rem
            } else {
                0
            }

            val crc = CRC32().apply { update(data) }.value
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = crc
            if (pad > 0) {
                entry.extra = ByteArray(pad)
            }
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
        zos.flush()
    }

    private fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper.startsWith("META-INF/SIG-")
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len.toLong()
        }
    }

    /** Utility for unit tests to inspect exact data offsets of entries in a ZIP file */
    internal fun inspectStoredDataOffsets(zipFile: File): Map<String, Long> {
        val raw = zipFile.readBytes()
        val result = linkedMapOf<String, Long>()
        var offset = 0
        while (offset + 30 <= raw.size) {
            val sig = readIntLE(raw, offset)
            if (sig != 0x04034b50) break
            val method = readShortLE(raw, offset + 8)
            val compSize = readIntLE(raw, offset + 18).toLong() and 0xFFFFFFFFL
            val nameLen = readShortLE(raw, offset + 26)
            val extraLen = readShortLE(raw, offset + 28)
            val name = String(raw, offset + 30, nameLen, Charsets.UTF_8)
            val dataOffset = offset + 30 + nameLen + extraLen
            if (method == ZipEntry.STORED) {
                result[name] = dataOffset.toLong()
            }
            offset = dataOffset + compSize.toInt()
        }
        return result
    }

    private fun readShortLE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
}
