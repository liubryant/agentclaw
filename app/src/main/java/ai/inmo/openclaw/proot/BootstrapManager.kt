package ai.inmo.openclaw.proot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/**
 * InputStream wrapper that limits reads to a given number of bytes.
 * Needed for reading a single asset from an APK's shared file descriptor.
 */
private class BoundedInputStream(
    private val source: InputStream,
    private var remaining: Long
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }
    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val read = source.read(buf, off, toRead)
        if (read > 0) remaining -= read
        return read
    }
    override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()
    override fun close() { source.close() }
}

private class CountingInputStream(
    private val source: InputStream
) : InputStream() {
    @Volatile
    var bytesRead: Long = 0
        private set

    override fun read(): Int {
        val b = source.read()
        if (b >= 0) bytesRead++
        return b
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val read = source.read(buf, off, len)
        if (read > 0) bytesRead += read.toLong()
        return read
    }

    override fun available(): Int = source.available()
    override fun close() { source.close() }
}

class BootstrapManager(
    private val context: Context,
    private val filesDir: String,
    private val nativeLibDir: String
) {
    companion object {
        private const val TAG = "BootstrapManager"
        private const val BUF_SIZE_LARGE = 512 * 1024   // 512 KB
        private const val BUF_SIZE_ASSET = 1024 * 1024   // 1 MB
        private const val LARGE_FILE_THRESHOLD = 256 * 1024L  // 256 KB
        private const val MAX_PENDING_BYTES = 64L * 1024 * 1024 // 64 MB
    }

    @Volatile
    private var nativeTarAvailable: Boolean? = null

    @Volatile
    var extractionEntryCount: Int = 0

    data class ExtractionProgress(
        val phase: String = "idle",
        val entriesDone: Int = 0,
        val entriesTotal: Int = 0,
        val percent: Int = 0,
        val currentEntry: String = "",
        val done: Boolean = false
    )

    @Volatile
    private var extractionProgress = ExtractionProgress()

    private val rootfsDir get() = "$filesDir/rootfs/ubuntu"
    private val tmpDir get() = "$filesDir/tmp"
    private val homeDir get() = "$filesDir/home"
    private val configDir get() = "$filesDir/config"
    private val libDir get() = "$filesDir/lib"

    @Synchronized
    private fun setExtractionProgress(
        phase: String,
        entriesDone: Int,
        entriesTotal: Int,
        currentEntry: String,
        done: Boolean,
        explicitPercent: Int? = null
    ) {
        val percent = explicitPercent?.coerceIn(0, 100) ?: run {
            if (entriesTotal <= 0) {
                if (done) 100 else 0
            } else {
                ((entriesDone * 100.0) / entriesTotal).toInt().coerceIn(0, 100)
            }
        }
        extractionProgress = ExtractionProgress(
            phase = phase,
            entriesDone = entriesDone,
            entriesTotal = entriesTotal,
            percent = percent,
            currentEntry = currentEntry,
            done = done
        )
        extractionEntryCount = entriesDone
    }

    fun getExtractionProgressDetail(): Map<String, Any> {
        val snapshot = extractionProgress
        return mapOf(
            "phase" to snapshot.phase,
            "entriesDone" to snapshot.entriesDone,
            "entriesTotal" to snapshot.entriesTotal,
            "percent" to snapshot.percent,
            "currentEntry" to snapshot.currentEntry,
            "done" to snapshot.done
        )
    }

    private fun shortenEntry(name: String, maxLength: Int = 120): String {
        if (name.length <= maxLength) return name
        val keep = maxLength - 3
        return "..." + name.takeLast(keep.coerceAtLeast(1))
    }

    /**
     * Check if a bundled asset exists in the APK.
     * Uses openFd() instead of open() to avoid loading large files into memory.
     * openFd() only works for uncompressed assets (requires noCompress in build.gradle).
     */
    fun hasBundledAsset(assetName: String): Boolean {
        // Try openFd() first — works only for uncompressed assets
        try {
            val afd = context.assets.openFd(assetName)
            val size = afd.length
            afd.close()
            android.util.Log.i(TAG, "hasBundledAsset: openFd OK for '$assetName', size=$size")
            return true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "hasBundledAsset: openFd failed for '$assetName': ${e::class.simpleName}: ${e.message}")
        }
        // Fall back to list() — works for both compressed and uncompressed
        try {
            val files = context.assets.list("") ?: emptyArray()
            android.util.Log.i(TAG, "hasBundledAsset: assets.list('') = [${files.joinToString()}]")
            val found = files.contains(assetName)
            android.util.Log.i(TAG, "hasBundledAsset: '$assetName' found via list()=$found")
            return found
        } catch (e: Exception) {
            android.util.Log.e(TAG, "hasBundledAsset: list() also failed: ${e.message}")
        }
        return false
    }

    /**
     * Stream-copy a bundled asset to a filesystem path.
     * Uses openFd() + FileInputStream to avoid loading the entire file into memory,
     * which is critical for large assets (hundreds of MB).
     */
    fun copyAssetToFile(assetName: String, destPath: String) {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val afd = context.assets.openFd(assetName)
        afd.use {
            FileInputStream(afd.fileDescriptor).use { fis ->
                // openFd() may share the underlying file — seek to the asset's start offset
                fis.channel.position(afd.startOffset)
                val remaining = afd.length
                BufferedInputStream(fis, 256 * 1024).use { bis ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(256 * 1024)
                        var copied = 0L
                        while (copied < remaining) {
                            val toRead = minOf(buf.size.toLong(), remaining - copied).toInt()
                            val read = bis.read(buf, 0, toRead)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            copied += read
                        }
                    }
                }
            }
        }
    }

    fun setupDirectories() {
        listOf(rootfsDir, tmpDir, homeDir, configDir, "$homeDir/.openclaw", libDir).forEach {
            File(it).mkdirs()
        }
        // Termux's proot links against libtalloc.so.2 but Android extracts it
        // as libtalloc.so (jniLibs naming convention). Create a copy with the
        // correct SONAME so the dynamic linker finds it.
        setupLibtalloc()
        // Create fake /proc and /sys files for proot bind mounts
        setupFakeSysdata()
    }

    private fun setupLibtalloc() {
        val source = File("$nativeLibDir/libtalloc.so")
        val target = File("$libDir/libtalloc.so.2")
        if (source.exists() && !target.exists()) {
            source.copyTo(target)
            target.setExecutable(true)
        }
    }

    fun isBootstrapComplete(): Boolean {
        val rootfs = File(rootfsDir)
        val binBash = File("$rootfsDir/bin/bash")
        val bypass = File("$rootfsDir/root/.openclaw/bionic-bypass.js")
        val hook = File("$rootfsDir/root/.openclaw/hook.js")
        val node = File("$rootfsDir/usr/local/bin/node")
        val openclaw = File("$rootfsDir/usr/local/lib/node_modules/openclaw/package.json")
        return rootfs.exists() && binBash.exists() && bypass.exists()
            && hook.exists() && node.exists() && openclaw.exists()
    }

    fun getBootstrapStatus(): Map<String, Any> {
        val rootfsExists = File(rootfsDir).exists()
        val binBashExists = File("$rootfsDir/bin/bash").exists()
        val nodeExists = File("$rootfsDir/usr/local/bin/node").exists()
        val openclawExists = File("$rootfsDir/usr/local/lib/node_modules/openclaw/package.json").exists()
        val bypassExists = File("$rootfsDir/root/.openclaw/bionic-bypass.js").exists()
        val hookExists = File("$rootfsDir/root/.openclaw/hook.js").exists()

        return mapOf(
            "rootfsExists" to rootfsExists,
            "binBashExists" to binBashExists,
            "nodeInstalled" to nodeExists,
            "openclawInstalled" to openclawExists,
            "bypassInstalled" to (bypassExists && hookExists),
            "hookInstalled" to hookExists,
            "rootfsPath" to rootfsDir,
            "complete" to (rootfsExists && binBashExists && bypassExists
                && hookExists && nodeExists && openclawExists)
        )
    }

    fun extractRootfs(tarPath: String) {
        val tarFile = File(tarPath)
        extractRootfsFromStream(
            streamFactory = {
                BufferedInputStream(FileInputStream(tarFile), BUF_SIZE_LARGE)
            },
            totalCompressedBytes = tarFile.length()
        )
        // Clean up tarball
        File(tarPath).delete()
    }

    /**
     * Extract a prebundled rootfs directly from an APK asset, skipping the
     * intermediate file copy. Streams asset → gzip → tar → rootfs in one pass.
     */
    fun extractRootfsFromAsset(assetName: String) {
        val afd = context.assets.openFd(assetName)
        extractRootfsFromStream(
            assetName = assetName,
            streamFactory = {
                val fis = FileInputStream(afd.fileDescriptor)
                fis.channel.position(afd.startOffset)
                BufferedInputStream(BoundedInputStream(fis, afd.length), BUF_SIZE_LARGE)
            },
            totalCompressedBytes = afd.length
        )
        afd.close()
    }

    // ====================================================================
    // Native tar extraction (primary fast path)
    // ====================================================================

    private fun isNativeTarAvailable(): Boolean {
        nativeTarAvailable?.let { return it }
        val tar = File("/system/bin/tar")
        val available = tar.exists() && tar.canExecute()
        if (available) {
            android.util.Log.i(TAG, "Native tar available at /system/bin/tar")
        } else {
            android.util.Log.i(TAG, "Native tar not available, will use Java extraction")
        }
        nativeTarAvailable = available
        return available
    }

    /**
     * Try pipe-based native tar: stream asset directly into tar's stdin.
     * No temp file needed — reads and extracts simultaneously.
     * Returns true on success, false on failure (caller should fallback).
     */
    private fun tryPipedNativeTarExtraction(
        assetName: String,
        totalCompressedBytes: Long
    ): Boolean {
        android.util.Log.i(TAG, "Attempting piped native tar extraction")
        setExtractionProgress("native-tar-pipe", 0, 0, "", done = false, explicitPercent = 0)

        val rootfs = File(rootfsDir)
        rootfs.mkdirs()

        try {
            val pb = ProcessBuilder("/system/bin/tar", "xz", "-C", rootfsDir)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val errorRef = AtomicReference<String>(null)
            val startedAtMs = System.currentTimeMillis()

            // Thread: read stderr
            val stderrThread = Thread {
                try {
                    val stderr = process.errorStream.bufferedReader().readText()
                    if (stderr.isNotBlank()) {
                        errorRef.set(stderr.take(500))
                        android.util.Log.w(TAG, "Native tar stderr: ${stderr.take(500)}")
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            // Thread: pipe asset into tar's stdin with progress tracking
            val pipeError = AtomicReference<Exception>(null)
            val pipeAfd = context.assets.openFd(assetName)
            val countingIn = CountingInputStream(
                BufferedInputStream(
                    BoundedInputStream(
                        FileInputStream(pipeAfd.fileDescriptor).also {
                            it.channel.position(pipeAfd.startOffset)
                        },
                        pipeAfd.length
                    ),
                    BUF_SIZE_ASSET
                )
            )

            val pipeThread = Thread {
                try {
                    val out = BufferedOutputStream(process.outputStream, BUF_SIZE_ASSET)
                    val buf = ByteArray(BUF_SIZE_ASSET)
                    var lastPublishMs = 0L
                    while (true) {
                        val read = countingIn.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        // Publish progress
                        val now = System.currentTimeMillis()
                        if (now - lastPublishMs >= 300) {
                            val percent = ((countingIn.bytesRead * 100.0) / totalCompressedBytes)
                                .toInt().coerceIn(0, 99)
                            setExtractionProgress(
                                "native-tar-pipe", 0, 0, "",
                                done = false, explicitPercent = percent
                            )
                            lastPublishMs = now
                        }
                    }
                    out.flush()
                    out.close()
                    countingIn.close()
                } catch (e: Exception) {
                    pipeError.set(e)
                    try { process.outputStream.close() } catch (_: Exception) {}
                    try { countingIn.close() } catch (_: Exception) {}
                }
            }.apply { isDaemon = true; start() }

            // Wait for tar to finish
            val completed = process.waitFor(600, TimeUnit.SECONDS)
            pipeThread.join(10_000)
            stderrThread.join(5_000)
            try { pipeAfd.close() } catch (_: Exception) {}

            if (!completed) {
                process.destroyForcibly()
                android.util.Log.e(TAG, "Native tar timed out")
                return false
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                android.util.Log.e(TAG, "Native tar failed with exit code $exitCode, stderr: ${errorRef.get()}")
                return false
            }

            pipeError.get()?.let {
                android.util.Log.e(TAG, "Pipe error: ${it.message}")
                return false
            }

            // Verify extraction
            if (!File("$rootfsDir/bin/bash").exists() &&
                !File("$rootfsDir/usr/bin/bash").exists()) {
                android.util.Log.e(TAG, "Native tar extraction: bash not found")
                return false
            }

            val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
            android.util.Log.i(TAG, String.format(Locale.US,
                "Native tar pipe extraction complete in %.1fs", elapsedSec))
            setExtractionProgress("done", 0, 0, "", done = true, explicitPercent = 100)
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Native tar pipe failed: ${e.message}")
            return false
        }
    }

    /**
     * Fallback: copy asset to temp file, then extract with native tar.
     * Slower than pipe mode but more compatible.
     */
    private fun tryFiledNativeTarExtraction(assetName: String, totalCompressedBytes: Long): Boolean {
        android.util.Log.i(TAG, "Attempting file-based native tar extraction")
        val tmpFile = File("$filesDir/tmp/rootfs-extract.tar.gz")
        try {
            // Phase 1: Copy asset to temp file with progress
            setExtractionProgress("copying", 0, 0, "", done = false, explicitPercent = 0)
            tmpFile.parentFile?.mkdirs()
            val afd = context.assets.openFd(assetName)
            afd.use {
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.position(afd.startOffset)
                    val bounded = BoundedInputStream(fis, afd.length)
                    BufferedInputStream(bounded, BUF_SIZE_ASSET).use { bis ->
                        FileOutputStream(tmpFile).use { out ->
                            val buf = ByteArray(BUF_SIZE_ASSET)
                            var copied = 0L
                            var lastPublishMs = 0L
                            while (copied < totalCompressedBytes) {
                                val read = bis.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                                copied += read
                                val now = System.currentTimeMillis()
                                if (now - lastPublishMs >= 300) {
                                    val percent = ((copied * 40.0) / totalCompressedBytes)
                                        .toInt().coerceIn(0, 40)
                                    setExtractionProgress(
                                        "copying", 0, 0, "",
                                        done = false, explicitPercent = percent
                                    )
                                    lastPublishMs = now
                                }
                            }
                        }
                    }
                }
            }

            // Phase 2: Extract with native tar
            setExtractionProgress("native-tar-file", 0, 0, "", done = false, explicitPercent = 42)
            val rootfs = File(rootfsDir)
            rootfs.mkdirs()

            val startedAtMs = System.currentTimeMillis()
            val pb = ProcessBuilder("/system/bin/tar", "xzf", tmpFile.absolutePath, "-C", rootfsDir)
            pb.redirectErrorStream(true)
            val process = pb.start()

            // Read output to prevent pipe blocking
            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(600, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                android.util.Log.e(TAG, "Native tar (file) timed out")
                return false
            }

            if (process.exitValue() != 0) {
                android.util.Log.e(TAG, "Native tar (file) failed: $output")
                return false
            }

            // Verify
            if (!File("$rootfsDir/bin/bash").exists() &&
                !File("$rootfsDir/usr/bin/bash").exists()) {
                android.util.Log.e(TAG, "Native tar (file): bash not found")
                return false
            }

            val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
            android.util.Log.i(TAG, String.format(Locale.US,
                "Native tar file extraction complete in %.1fs", elapsedSec))
            setExtractionProgress("done", 0, 0, "", done = true, explicitPercent = 100)
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Native tar (file) failed: ${e.message}")
            return false
        } finally {
            tmpFile.delete()
        }
    }

    // ====================================================================
    // Multi-threaded Java extraction (fallback path)
    // ====================================================================

    private sealed class ExtractTask {
        data class WriteFile(
            val path: String,
            val data: ByteArray,
            val size: Int,
            val executable: Boolean
        ) : ExtractTask()
        object Poison : ExtractTask()
    }

    /**
     * Optimized multi-threaded extraction pipeline.
     * Producer: decompress + parse tar entries.
     * Consumers: write files to disk in parallel.
     */
    private fun extractWithParallelPipeline(
        streamFactory: () -> InputStream,
        totalCompressedBytes: Long
    ) {
        val startedAtMs = System.currentTimeMillis()
        setExtractionProgress("extracting", 0, 0, "", done = false, explicitPercent = 0)
        android.util.Log.i(TAG,
            "Parallel Java extraction: compressedSize=$totalCompressedBytes, target=$rootfsDir")

        val rootfs = File(rootfsDir)
        val createdDirs = ConcurrentHashMap.newKeySet<String>()
        createdDirs.add(rootfs.absolutePath)

        fun ensureDirExists(dir: File?) {
            if (dir == null) return
            val path = dir.absolutePath
            if (createdDirs.contains(path)) return
            if (!dir.exists()) dir.mkdirs()
            createdDirs.add(path)
        }

        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val queue = LinkedBlockingQueue<ExtractTask>(256)
        val pendingBytes = AtomicLong(0)
        val semaphore = Semaphore((MAX_PENDING_BYTES / (64 * 1024)).toInt().coerceAtLeast(16))
        val consumerError = AtomicReference<Exception>(null)
        val filesWritten = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        // Launch consumers
        val consumers = (1..threadCount).map {
            executor.submit {
                try {
                    while (true) {
                        val task = queue.take()
                        if (task is ExtractTask.Poison) {
                            // Re-add poison for other consumers
                            queue.put(task)
                            break
                        }
                        if (consumerError.get() != null) continue
                        if (task is ExtractTask.WriteFile) {
                            try {
                                val outFile = File(task.path)
                                BufferedOutputStream(FileOutputStream(outFile), BUF_SIZE_LARGE).use { bos ->
                                    bos.write(task.data, 0, task.size)
                                }
                                if (task.executable) {
                                    outFile.setExecutable(true, false)
                                }
                                filesWritten.incrementAndGet()
                            } catch (e: Exception) {
                                consumerError.compareAndSet(null, e)
                            } finally {
                                pendingBytes.addAndGet(-task.size.toLong())
                                semaphore.release()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val deferredSymlinks = ConcurrentLinkedQueue<Pair<String, String>>()
        var entryCount = 0
        var symlinkCount = 0
        var lastPercent = 0
        lateinit var countingIn: CountingInputStream

        var lastPublishMs = 0L
        var lastPublishedPercent = 0
        fun publishProgress(currentEntry: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val percent = if (totalCompressedBytes > 0) {
                ((countingIn.bytesRead * 100.0) / totalCompressedBytes)
                    .toInt().coerceIn(lastPercent, 99)
            } else 0
            val shouldPublish = force || percent != lastPublishedPercent || now - lastPublishMs >= 200
            if (!shouldPublish) return
            setExtractionProgress("extracting", entryCount, 0, currentEntry,
                done = false, explicitPercent = percent)
            lastPublishMs = now
            lastPublishedPercent = percent
            lastPercent = percent
        }

        var extractionError: Exception? = null
        try {
            val rawCompressed = streamFactory()
            countingIn = CountingInputStream(rawCompressed)
            GZIPInputStream(BufferedInputStream(countingIn, BUF_SIZE_LARGE)).use { decompressed ->
                TarArchiveInputStream(decompressed).use { tis ->
                    var entry: TarArchiveEntry? = tis.nextEntry
                    while (entry != null) {
                        // Check for consumer errors
                        consumerError.get()?.let { throw it }

                        entryCount++
                        val name = entry.name.removePrefix("./").removePrefix("/")
                        publishProgress(shortenEntry(name))

                        if (name.isEmpty() || name.startsWith("dev/") || name == "dev") {
                            entry = tis.nextEntry
                            continue
                        }

                        val outFile = File(rootfsDir, name)

                        when {
                            entry.isDirectory -> {
                                // Directories: create synchronously (must exist before files)
                                ensureDirExists(outFile)
                            }
                            entry.isSymbolicLink -> {
                                ensureDirExists(outFile.parentFile)
                                deferredSymlinks.add(Pair(entry.linkName, outFile.absolutePath))
                                symlinkCount++
                            }
                            entry.isLink -> {
                                // Hard links: process synchronously
                                val target = entry.linkName.removePrefix("./").removePrefix("/")
                                val targetFile = File(rootfsDir, target)
                                ensureDirExists(outFile.parentFile)
                                try {
                                    if (targetFile.exists()) {
                                        targetFile.copyTo(outFile, overwrite = true)
                                        if (targetFile.canExecute()) {
                                            outFile.setExecutable(true, false)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            else -> {
                                ensureDirExists(outFile.parentFile)
                                val entrySize = entry.size
                                val mode = entry.mode
                                val executable = mode and 0b001_001_001 != 0 ||
                                    name.lowercase().let { p ->
                                        p.contains("/bin/") || p.contains("/sbin/") ||
                                            p.endsWith(".sh") || p.contains("/lib/apt/methods/")
                                    }

                                if (entrySize <= LARGE_FILE_THRESHOLD) {
                                    // Small file: buffer and enqueue to thread pool
                                    val data = ByteArray(entrySize.toInt().coerceAtLeast(1))
                                    var offset = 0
                                    while (offset < entrySize) {
                                        val read = tis.read(data, offset, (entrySize - offset).toInt())
                                        if (read == -1) break
                                        offset += read
                                    }
                                    semaphore.acquire()
                                    pendingBytes.addAndGet(offset.toLong())
                                    queue.put(ExtractTask.WriteFile(
                                        outFile.absolutePath, data, offset, executable))
                                } else {
                                    // Large file: write directly from producer thread
                                    BufferedOutputStream(FileOutputStream(outFile), BUF_SIZE_LARGE).use { bos ->
                                        val buf = ByteArray(BUF_SIZE_LARGE)
                                        var len: Int
                                        while (tis.read(buf).also { len = it } != -1) {
                                            bos.write(buf, 0, len)
                                        }
                                    }
                                    if (executable) outFile.setExecutable(true, false)
                                    filesWritten.incrementAndGet()
                                }
                            }
                        }
                        entry = tis.nextEntry
                    }
                }
            }
            publishProgress("", force = true)
        } catch (e: Exception) {
            extractionError = e
            setExtractionProgress("error", entryCount, 0, "", done = false, explicitPercent = lastPercent)
        }

        // Signal consumers to stop and wait
        queue.put(ExtractTask.Poison)
        executor.shutdown()
        executor.awaitTermination(60, TimeUnit.SECONDS)

        // Check for consumer errors
        consumerError.get()?.let { extractionError = extractionError ?: it }

        if (entryCount == 0) {
            throw RuntimeException(
                "Extraction failed: tarball appears empty or corrupt. " +
                    "Error: ${extractionError?.message ?: "none"}")
        }

        val totalFiles = filesWritten.get()
        if (extractionError != null && totalFiles < 100) {
            throw RuntimeException(
                "Extraction failed after $entryCount entries ($totalFiles files): " +
                    "${extractionError!!.message}")
        }

        // Phase 2: Create all symlinks
        var symlinkErrors = 0
        var lastSymlinkError = ""
        for ((target, path) in deferredSymlinks) {
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.isDirectory) {
                        val linkTarget = if (target.startsWith("/")) {
                            target.removePrefix("/")
                        } else {
                            val parent = file.parentFile?.absolutePath ?: rootfsDir
                            File(parent, target).relativeTo(File(rootfsDir)).path
                        }
                        val realTargetDir = File(rootfsDir, linkTarget)
                        if (realTargetDir.exists() && realTargetDir.isDirectory) {
                            file.listFiles()?.forEach { child ->
                                val dest = File(realTargetDir, child.name)
                                if (!dest.exists()) child.renameTo(dest)
                            }
                        }
                        deleteRecursively(file)
                    } else {
                        file.delete()
                    }
                }
                ensureDirExists(file.parentFile)
                Os.symlink(target, path)
            } catch (e: Exception) {
                symlinkErrors++
                lastSymlinkError = "$path -> $target: ${e.message}"
            }
        }

        // Verify extraction
        if (!File("$rootfsDir/bin/bash").exists() &&
            !File("$rootfsDir/usr/bin/bash").exists()) {
            setExtractionProgress("error", entryCount, 0, "", done = false, explicitPercent = lastPercent)
            throw RuntimeException(
                "Extraction failed: bash not found in rootfs. " +
                    "Processed $entryCount entries, $totalFiles files, " +
                    "$symlinkCount symlinks ($symlinkErrors symlink errors). " +
                    "Last symlink error: $lastSymlinkError. " +
                    "usr/bin exists: ${File("$rootfsDir/usr/bin").exists()}. " +
                    "Extraction error: ${extractionError?.message ?: "none"}")
        }

        configureRootfs(skipPermissionFix = true)

        setExtractionProgress("done", entryCount, 0, "", done = true, explicitPercent = 100)
        val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
        android.util.Log.i(TAG, String.format(Locale.US,
            "Parallel Java extraction complete in %.1fs: entries=%d, files=%d, symlinks=%d, threads=%d",
            elapsedSec, entryCount, totalFiles, symlinkCount, threadCount))
    }

    /**
     * Core rootfs extraction dispatcher. Tries strategies in order:
     * 1. Pipe + native tar (fastest, ~15-25s)
     * 2. Temp file + native tar (~30-45s)
     * 3. Multi-threaded Java pipeline (fallback, ~60-90s)
     */
    private fun extractRootfsFromStream(
        streamFactory: () -> InputStream,
        totalCompressedBytes: Long,
        assetName: String? = null
    ) {
        val startedAtMs = System.currentTimeMillis()
        setExtractionProgress("extracting", 0, 0, "", done = false, explicitPercent = 0)
        android.util.Log.i(TAG,
            "Rootfs extraction start: compressedSize=$totalCompressedBytes, target=$rootfsDir")

        // Prepare rootfs directory
        val rootfs = File(rootfsDir)
        if (rootfs.exists()) {
            deleteRecursively(rootfs)
        }
        rootfs.mkdirs()

        // Try native tar strategies first (much faster than Java)
        if (assetName != null && isNativeTarAvailable()) {
            // Strategy 1: Pipe asset directly to tar's stdin (fastest, no temp file)
            if (tryPipedNativeTarExtraction(assetName, totalCompressedBytes)) {
                configureRootfs(skipPermissionFix = true)
                val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
                android.util.Log.i(TAG, String.format(Locale.US,
                    "Rootfs extraction (native tar pipe) total %.1fs", elapsedSec))
                return
            }

            // Strategy 2: Copy to temp file, then extract with native tar
            android.util.Log.w(TAG, "Pipe mode failed, trying file-based native tar")
            // Re-create rootfs dir (pipe attempt may have partially written)
            if (rootfs.exists()) deleteRecursively(rootfs)
            rootfs.mkdirs()

            if (tryFiledNativeTarExtraction(assetName, totalCompressedBytes)) {
                configureRootfs(skipPermissionFix = true)
                val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
                android.util.Log.i(TAG, String.format(Locale.US,
                    "Rootfs extraction (native tar file) total %.1fs", elapsedSec))
                return
            }

            // Native tar failed entirely, fall through to Java
            android.util.Log.w(TAG, "Native tar failed, falling back to Java extraction")
            if (rootfs.exists()) deleteRecursively(rootfs)
            rootfs.mkdirs()
        }

        // Strategy 3: Optimized multi-threaded Java extraction (fallback)
        extractWithParallelPipeline(streamFactory, totalCompressedBytes)
        val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
        android.util.Log.i(TAG, String.format(Locale.US,
            "Rootfs extraction (Java parallel) total %.1fs", elapsedSec))
    }

    /**
     * Extract all .deb packages from the apt cache into the rootfs.
     * Uses Java (Apache Commons Compress) to avoid fork+exec issues in proot.
     * A .deb is an ar archive containing data.tar.{xz,gz,zst}.
     * Returns the number of packages extracted.
     */
    fun extractDebPackages(): Int {
        val archivesDir = File("$rootfsDir/var/cache/apt/archives")
        if (!archivesDir.exists()) {
            throw RuntimeException("No apt archives directory found")
        }

        val debFiles = archivesDir.listFiles { f -> f.name.endsWith(".deb") }
            ?: throw RuntimeException("No .deb files found in apt cache")

        if (debFiles.isEmpty()) {
            throw RuntimeException("No .deb files found in apt cache")
        }

        var extracted = 0
        val errors = mutableListOf<String>()

        for (debFile in debFiles) {
            try {
                extractSingleDeb(debFile)
                extracted++
            } catch (e: Exception) {
                errors.add("${debFile.name}: ${e.message}")
            }
        }

        if (extracted == 0) {
            throw RuntimeException(
                "Failed to extract any .deb packages. Errors: ${errors.joinToString("; ")}"
            )
        }

        // Fix permissions on newly extracted binaries
        fixBinPermissions()

        return extracted
    }

    /**
     * Extract a single .deb file into the rootfs.
     * Reads the ar archive, finds data.tar.*, decompresses, and extracts.
     */
    private fun extractSingleDeb(debFile: File) {
        FileInputStream(debFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                ArArchiveInputStream(bis).use { arIn ->
                    var arEntry = arIn.nextEntry
                    while (arEntry != null) {
                        val name = arEntry.name
                        if (name.startsWith("data.tar")) {
                            // Wrap in appropriate decompressor
                            val dataStream: InputStream = when {
                                name.endsWith(".xz") -> XZCompressorInputStream(arIn)
                                name.endsWith(".gz") -> GZIPInputStream(arIn)
                                name.endsWith(".zst") -> ZstdCompressorInputStream(arIn)
                                else -> arIn // plain .tar or unknown
                            }

                            // Extract data.tar contents into rootfs
                            TarArchiveInputStream(dataStream).use { tarIn ->
                                var tarEntry = tarIn.nextEntry
                                while (tarEntry != null) {
                                    val entryName = tarEntry.name
                                        .removePrefix("./")
                                        .removePrefix("/")

                                    if (entryName.isEmpty()) {
                                        tarEntry = tarIn.nextEntry
                                        continue
                                    }

                                    val outFile = File(rootfsDir, entryName)

                                    when {
                                        tarEntry.isDirectory -> {
                                            outFile.mkdirs()
                                        }
                                        tarEntry.isSymbolicLink -> {
                                            try {
                                                if (outFile.exists()) outFile.delete()
                                                outFile.parentFile?.mkdirs()
                                                Os.symlink(tarEntry.linkName, outFile.absolutePath)
                                            } catch (_: Exception) {}
                                        }
                                        tarEntry.isLink -> {
                                            val target = tarEntry.linkName
                                                .removePrefix("./")
                                                .removePrefix("/")
                                            val targetFile = File(rootfsDir, target)
                                            outFile.parentFile?.mkdirs()
                                            try {
                                                if (targetFile.exists()) {
                                                    targetFile.copyTo(outFile, overwrite = true)
                                                    if (targetFile.canExecute()) {
                                                        outFile.setExecutable(true, false)
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        else -> {
                                            outFile.parentFile?.mkdirs()
                                            FileOutputStream(outFile).use { fos ->
                                                val buf = ByteArray(65536)
                                                var len: Int
                                                while (tarIn.read(buf).also { len = it } != -1) {
                                                    fos.write(buf, 0, len)
                                                }
                                            }
                                            outFile.setReadable(true, false)
                                            outFile.setWritable(true, false)
                                            val mode = tarEntry.mode
                                            if (mode and 0b001_001_001 != 0) {
                                                outFile.setExecutable(true, false)
                                            }
                                            // Ensure bin/sbin files are executable
                                            val path = entryName.lowercase()
                                            if (path.contains("/bin/") ||
                                                path.contains("/sbin/")) {
                                                outFile.setExecutable(true, false)
                                            }
                                        }
                                    }

                                    tarEntry = tarIn.nextEntry
                                }
                            }
                            return // Found and processed data.tar, done
                        }
                        arEntry = arIn.nextEntry
                    }
                }
            }
        }
    }

    /**
     * Write configuration files that make the rootfs work correctly under proot.
     * Called automatically after extraction.
     */
    private fun configureRootfs(skipPermissionFix: Boolean = false) {
        // 1. Disable apt sandboxing — proot fakes UID 0 via ptrace but cannot
        //    intercept setresuid/setresgid, so apt's _apt user privilege drop
        //    fails with "Operation not permitted". Tell apt to stay as root.
        val aptConfDir = File("$rootfsDir/etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "01-openclaw-proot").writeText(
            "APT::Sandbox::User \"root\";\n" +
            // Disable PTY allocation when APT forks dpkg. APT's child process
            // calls SetupSlavePtyMagic() before execvp(dpkg); in proot on
            // Android 10+ (W^X policy), the PTY/chdir setup in the child can
            // fail causing _exit(100). Disabling this simplifies the fork path.
            "Dpkg::Use-Pty \"0\";\n" +
            // Pass dpkg options through apt to tolerate proot failures
            "Dpkg::Options { \"--force-confnew\"; \"--force-overwrite\"; };\n"
        )

        // 2. Configure dpkg for proot compatibility
        //    - force-unsafe-io: skip fsync/sync_file_range (may ENOSYS in proot)
        //    - no-debsig: skip signature verification
        val dpkgConfDir = File("$rootfsDir/etc/dpkg/dpkg.cfg.d")
        dpkgConfDir.mkdirs()
        File(dpkgConfDir, "01-openclaw-proot").writeText(
            "force-unsafe-io\n" +
            "no-debsig\n" +
            "force-overwrite\n" +
            "force-depends\n"
        )

        // 3. Ensure essential directories exist
        // mkdir syscall is broken inside proot on Android 10+.
        // Pre-create ALL directories that tools need at runtime.
        listOf(
            "$rootfsDir/etc/ssl/certs",
            "$rootfsDir/usr/share/keyrings",
            "$rootfsDir/etc/apt/sources.list.d",
            "$rootfsDir/var/lib/dpkg/updates",
            "$rootfsDir/var/lib/dpkg/triggers",
            // npm cache directories (npm can't mkdir inside proot)
            "$rootfsDir/tmp/npm-cache/_cacache/tmp",
            "$rootfsDir/tmp/npm-cache/_cacache/content-v2",
            "$rootfsDir/tmp/npm-cache/_cacache/index-v5",
            "$rootfsDir/tmp/npm-cache/_logs",
            // Node.js / npm working directories
            "$rootfsDir/root/.npm",
            "$rootfsDir/root/.config",
            "$rootfsDir/usr/local/lib/node_modules",
            "$rootfsDir/usr/local/bin",
            // OpenClaw runtime directories (can't mkdir at runtime)
            "$rootfsDir/root/.openclaw",
            "$rootfsDir/root/.openclaw/data",
            "$rootfsDir/root/.openclaw/memory",
            "$rootfsDir/root/.openclaw/skills",
            "$rootfsDir/root/.openclaw/config",
            "$rootfsDir/root/.openclaw/extensions",
            "$rootfsDir/root/.openclaw/logs",
            "$rootfsDir/root/.config/openclaw",
            "$rootfsDir/root/.local/share",
            "$rootfsDir/root/.cache",
            "$rootfsDir/root/.cache/openclaw",
            "$rootfsDir/root/.cache/node",
            // General runtime directories
            "$rootfsDir/var/tmp",
            "$rootfsDir/run",
            "$rootfsDir/run/lock",
            "$rootfsDir/dev/shm",
        ).forEach { File(it).mkdirs() }

        // 4. Ensure /etc/machine-id exists (dpkg triggers and systemd utils need it)
        val machineId = File("$rootfsDir/etc/machine-id")
        if (!machineId.exists()) {
            machineId.parentFile?.mkdirs()
            machineId.writeText("10000000000000000000000000000000\n")
        }

        // 4. Ensure policy-rc.d prevents services from auto-starting during install
        //    (they'd fail inside proot anyway)
        val policyRc = File("$rootfsDir/usr/sbin/policy-rc.d")
        policyRc.parentFile?.mkdirs()
        policyRc.writeText("#!/bin/sh\nexit 101\n")
        policyRc.setExecutable(true, false)

        // 5. Register Android user/groups in rootfs (matching proot-distro).
        //    dpkg and apt need valid user/group databases.
        registerAndroidUsers()

        // 6. Write /etc/hosts (some post-install scripts need hostname resolution)
        val hosts = File("$rootfsDir/etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("localhost")) {
            hosts.writeText(
                "127.0.0.1   localhost.localdomain localhost\n" +
                "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n"
            )
        }

        // 7. Ensure /tmp exists with world-writable + sticky permissions
        //    (needed for /dev/shm bind mount and general temp file usage)
        val tmpDir = File("$rootfsDir/tmp")
        tmpDir.mkdirs()
        tmpDir.setReadable(true, false)
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)

        // 8. Fix executable permissions on critical directories.
        //    Our Java extraction might not preserve all permission bits correctly
        //    (dpkg error 100 = "Could not exec dpkg" = permission issue).
        //    Recursively ensure all files in bin/sbin/lib dirs are executable.
        //    Skipped when native tar was used (preserves original mode bits)
        //    or when parallel Java pipeline already handled permissions.
        if (!skipPermissionFix) {
            fixBinPermissions()
        }
    }

    /**
     * Ensure all files in executable directories have the execute bit set.
     * Java's File API doesn't support full Unix permissions, so tar extraction
     * may leave some binaries without +x, causing "Could not exec dpkg" (error 100).
     */
    private fun fixBinPermissions() {
        // Directories whose files (recursively) must be executable
        val recursiveExecDirs = listOf(
            "$rootfsDir/usr/bin",
            "$rootfsDir/usr/sbin",
            "$rootfsDir/usr/local/bin",
            "$rootfsDir/usr/local/sbin",
            "$rootfsDir/usr/lib/apt/methods",
            "$rootfsDir/usr/lib/dpkg",
            "$rootfsDir/usr/lib/git-core",     // git sub-commands (git-remote-https, etc.)
            "$rootfsDir/usr/libexec",
            "$rootfsDir/var/lib/dpkg/info",    // dpkg maintainer scripts (preinst/postinst/prerm/postrm)
            "$rootfsDir/usr/share/debconf",    // debconf frontend scripts
            // These might be symlinks to usr/* in merged /usr, but
            // if they're real dirs we need to fix them too
            "$rootfsDir/bin",
            "$rootfsDir/sbin",
        )
        for (dirPath in recursiveExecDirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                fixExecRecursive(dir)
            }
        }
        // Also fix shared libraries (dpkg, apt, etc. link against them)
        val libDirs = listOf(
            "$rootfsDir/usr/lib",
            "$rootfsDir/lib",
        )
        for (dirPath in libDirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                fixSharedLibsRecursive(dir)
            }
        }
    }

    /** Recursively set +rx on all regular files in a directory tree. */
    private fun fixExecRecursive(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                fixExecRecursive(file)
            } else if (file.isFile) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun fixSharedLibsRecursive(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                fixSharedLibsRecursive(file)
            } else if (file.name.endsWith(".so") || file.name.contains(".so.")) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    /**
     * Register Android UID/GID in the rootfs user databases,
     * matching what proot-distro does during installation.
     * This ensures dpkg/apt can resolve user/group names.
     */
    private fun registerAndroidUsers() {
        val uid = android.os.Process.myUid()
        val gid = uid // On Android, primary GID == UID

        // Ensure files are writable
        for (name in listOf("passwd", "shadow", "group", "gshadow")) {
            val f = File("$rootfsDir/etc/$name")
            if (f.exists()) f.setWritable(true, false)
        }

        // Add Android app user to /etc/passwd
        val passwd = File("$rootfsDir/etc/passwd")
        if (passwd.exists()) {
            val content = passwd.readText()
            if (!content.contains("aid_android")) {
                passwd.appendText("aid_android:x:$uid:$gid:Android:/:/sbin/nologin\n")
            }
        }

        // Add to /etc/shadow
        val shadow = File("$rootfsDir/etc/shadow")
        if (shadow.exists()) {
            val content = shadow.readText()
            if (!content.contains("aid_android")) {
                shadow.appendText("aid_android:*:18446:0:99999:7:::\n")
            }
        }

        // Add Android groups to /etc/group
        val group = File("$rootfsDir/etc/group")
        if (group.exists()) {
            val content = group.readText()
            // Add common Android groups that packages might reference
            val groups = mapOf(
                "aid_inet" to 3003,       // Internet access
                "aid_net_raw" to 3004,    // Raw sockets
                "aid_sdcard_rw" to 1015,  // SD card write
                "aid_android" to gid,     // App's own group
            )
            for ((name, id) in groups) {
                if (!content.contains(name)) {
                    group.appendText("$name:x:$id:root,aid_android\n")
                }
            }
        }

        // Add to /etc/gshadow
        val gshadow = File("$rootfsDir/etc/gshadow")
        if (gshadow.exists()) {
            val content = gshadow.readText()
            val groups = listOf("aid_inet", "aid_net_raw", "aid_sdcard_rw", "aid_android")
            for (name in groups) {
                if (!content.contains(name)) {
                    gshadow.appendText("$name:*::root,aid_android\n")
                }
            }
        }
    }

    /**
     * Extract a Node.js binary tarball (.tar.xz) into the rootfs.
     * The tarball contains node-v22.x.x-linux-arm64/ with bin/, lib/, etc.
     * We extract its contents into /usr/local/ so node and npm are on PATH.
     * This bypasses the NodeSource repo (curl/gpg fail in proot).
     */
    fun extractNodeTarball(tarPath: String) {
        val startedAtMs = System.currentTimeMillis()
        val destDir = File("$rootfsDir/usr/local")
        destDir.mkdirs()

        var entryCount = 0
        setExtractionProgress("counting", 0, 0, "", done = false)
        val totalEntries = try {
            FileInputStream(tarPath).use { fis ->
                BufferedInputStream(fis, 256 * 1024).use { bis ->
                    XZCompressorInputStream(bis).use { xzis ->
                        TarArchiveInputStream(xzis).use { tis ->
                            var count = 0
                            var entry: TarArchiveEntry? = tis.nextEntry
                            while (entry != null) {
                                count++
                                entry = tis.nextEntry
                            }
                            count
                        }
                    }
                }
            }
        } catch (e: Exception) {
            setExtractionProgress("error", 0, 0, "", done = false)
            throw RuntimeException("Failed to scan Node.js tarball entries: ${e.message}")
        }
        setExtractionProgress("extracting", 0, totalEntries, "", done = false)
        android.util.Log.i(TAG, "Node extraction: total entries=$totalEntries")

        var lastPublishMs = 0L
        var lastPublishedPercent = -1
        fun publishProgress(currentEntry: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val percent = if (totalEntries <= 0) 0
            else ((entryCount * 100.0) / totalEntries).toInt().coerceIn(0, 100)
            val shouldPublish = force || percent != lastPublishedPercent || now - lastPublishMs >= 200
            if (!shouldPublish) return
            setExtractionProgress("extracting", entryCount, totalEntries, currentEntry, done = false)
            if (force || percent % 5 == 0 || percent != lastPublishedPercent) {
                android.util.Log.d(
                    TAG,
                    "Node extraction progress: ${percent}% ($entryCount/$totalEntries) current=$currentEntry"
                )
            }
            lastPublishMs = now
            lastPublishedPercent = percent
        }
        try {
            FileInputStream(tarPath).use { fis ->
                BufferedInputStream(fis, 256 * 1024).use { bis ->
                    XZCompressorInputStream(bis).use { xzis ->
                        TarArchiveInputStream(xzis).use { tis ->
                            var entry: TarArchiveEntry? = tis.nextEntry
                            while (entry != null) {
                                entryCount++
                                val name = entry.name
                                publishProgress(shortenEntry(name))

                                // Strip the top-level directory (node-v22.x.x-linux-arm64/)
                                val slashIdx = name.indexOf('/')
                                if (slashIdx < 0 || slashIdx == name.length - 1) {
                                    entry = tis.nextEntry
                                    continue
                                }
                                val relPath = name.substring(slashIdx + 1)
                                if (relPath.isEmpty()) {
                                    entry = tis.nextEntry
                                    continue
                                }

                                val outFile = File(destDir, relPath)

                                when {
                                    entry.isDirectory -> {
                                        outFile.mkdirs()
                                    }
                                    entry.isSymbolicLink -> {
                                        try {
                                            if (outFile.exists()) outFile.delete()
                                            outFile.parentFile?.mkdirs()
                                            Os.symlink(entry.linkName, outFile.absolutePath)
                                        } catch (_: Exception) {}
                                    }
                                    else -> {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { fos ->
                                            val buf = ByteArray(65536)
                                            var len: Int
                                            while (tis.read(buf).also { len = it } != -1) {
                                                fos.write(buf, 0, len)
                                            }
                                        }
                                        outFile.setReadable(true, false)
                                        outFile.setWritable(true, false)
                                        // Set executable for bin/ files and .so files
                                        val mode = entry.mode
                                        if (mode and 0b001_001_001 != 0 ||
                                            relPath.startsWith("bin/") ||
                                            relPath.contains(".so")) {
                                            outFile.setExecutable(true, false)
                                        }
                                    }
                                }

                                entry = tis.nextEntry
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            setExtractionProgress("error", entryCount, totalEntries, "", done = false)
            throw RuntimeException(
                "Node.js tarball extraction failed after $entryCount entries: ${e.message}"
            )
        }

        // Verify node binary exists
        val nodeBin = File("$rootfsDir/usr/local/bin/node")
        if (!nodeBin.exists()) {
            setExtractionProgress("error", entryCount, totalEntries, "", done = false)
            throw RuntimeException(
                "Node.js extraction failed: node binary not found at /usr/local/bin/node " +
                "(processed $entryCount entries)"
            )
        }
        nodeBin.setExecutable(true, false)

        // Clean up tarball
        File(tarPath).delete()
        setExtractionProgress("done", entryCount, totalEntries, "", done = true)
        val elapsedSec = (System.currentTimeMillis() - startedAtMs) / 1000.0
        android.util.Log.i(
            TAG,
            String.format(
                Locale.US,
                "Node extraction complete in %.1fs: entries=%d",
                elapsedSec,
                entryCount
            )
        )
    }

    /**
     * Create shell wrapper scripts in /usr/local/bin/ for a globally-installed
     * npm package. npm's `install -g` creates symlinks, but symlinks can fail
     * silently in proot. Shell wrappers are a reliable fallback.
     *
     * Reads the package.json `bin` field directly from the rootfs filesystem
     * (no shell escaping needed).
     */
    fun createBinWrappers(packageName: String) {
        val pkgDir = File("$rootfsDir/usr/local/lib/node_modules/$packageName")
        val pkgJson = File(pkgDir, "package.json")
        if (!pkgJson.exists()) {
            throw RuntimeException("Package not found: $pkgDir")
        }

        // Simple JSON parsing for the "bin" field
        val json = pkgJson.readText()
        val binDir = File("$rootfsDir/usr/local/bin")
        binDir.mkdirs()

        // Parse bin entries from package.json
        // "bin": "cli.js"  OR  "bin": {"openclaw": "bin/openclaw.js", ...}
        val binEntries = mutableMapOf<String, String>()

        val binMatch = Regex(""""bin"\s*:\s*(\{[^}]*\}|"[^"]*")""").find(json)
        if (binMatch != null) {
            val value = binMatch.groupValues[1]
            if (value.startsWith("{")) {
                // Object: {"name": "path", ...}
                Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(value).forEach {
                    binEntries[it.groupValues[1]] = it.groupValues[2]
                }
            } else {
                // String: "path" — use package name as bin name
                val path = value.trim('"')
                binEntries[packageName] = path
            }
        }

        if (binEntries.isEmpty()) {
            // Fallback: check for common entry points
            for (candidate in listOf("bin/$packageName.js", "bin/$packageName", "cli.js", "index.js")) {
                if (File(pkgDir, candidate).exists()) {
                    binEntries[packageName] = candidate
                    break
                }
            }
        }

        for ((name, relPath) in binEntries) {
            val binFile = File(binDir, name)
            // Only create wrapper if the symlink doesn't already work
            if (binFile.exists() && binFile.canExecute()) continue

            val target = "/usr/local/lib/node_modules/$packageName/$relPath"
            val wrapper = "#!/bin/sh\nexec node \"$target\" \"\$@\"\n"
            binFile.writeText(wrapper)
            binFile.setExecutable(true, false)
            binFile.setReadable(true, false)
        }
    }

    private fun deleteRecursively(file: File) {
        // CRITICAL: Do NOT follow symlinks — the rootfs contains symlinks
        // to /storage/emulated/0 (sdcard). Following them would delete the
        // user's photos, downloads, and other real files.

        // Path boundary check: refuse to delete anything outside filesDir.
        // This is a secondary safeguard against accidental data loss (#67, #63).
        try {
            if (!file.canonicalPath.startsWith(filesDir)) {
                return
            }
        } catch (_: Exception) {
            return // If we can't resolve the path, don't risk deleting
        }

        try {
            val path = file.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                file.delete()
                return
            }
        } catch (_: Exception) {}
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    fun installBionicBypass() {
        val bypassDir = File("$rootfsDir/root/.openclaw")
        bypassDir.mkdirs()

        // 1. CWD fix — proot's getcwd() syscall returns ENOSYS on Android 10+.
        //    process.cwd() is called by Node's CJS module resolver and npm.
        //    This MUST be loaded before any other module.
        val cwdFixContent = """
// OpenClaw CWD Fix - Auto-generated
// proot on Android 10+ returns ENOSYS for getcwd() syscall.
// Patch process.cwd to return /root on failure.
const _origCwd = process.cwd;
process.cwd = function() {
  try { return _origCwd.call(process); }
  catch(e) { return process.env.HOME || '/root'; }
};
""".trimIndent()
        File(bypassDir, "cwd-fix.js").writeText(cwdFixContent)

        // 2. Node wrapper — patches broken syscalls then runs the target script.
        //    Used during bootstrap (where NODE_OPTIONS must be unset).
        //    Usage: node /root/.openclaw/node-wrapper.js <script> [args...]
        val wrapperContent = """
// OpenClaw Node Wrapper - Auto-generated
// Patches broken proot syscalls, then loads the target script.
// Used for bootstrap-time npm operations.

// --- Load shared proot compatibility patches ---
require('/root/.openclaw/proot-compat.js');

// Load target script
const script = process.argv[2];
if (script) {
  process.argv = [process.argv[0], script, ...process.argv.slice(3)];
  require(script);
} else {
  console.log('Usage: node node-wrapper.js <script> [args...]');
  process.exit(1);
}
""".trimIndent()
        File(bypassDir, "node-wrapper.js").writeText(wrapperContent)

        // 3. Shared proot compatibility patches — used by both node-wrapper.js
        //    (bootstrap) and bionic-bypass.js (runtime).
        //    Patches: process.cwd, fs.mkdir, child_process.spawn, os.*, fs.rename,
        //    fs.watch, fs.chmod/chown.
        val prootCompatContent = """
// OpenClaw Proot Compatibility Layer - Auto-generated
// Patches all known broken syscalls in proot on Android 10+.
// This file is require()'d by both node-wrapper.js and bionic-bypass.js.

'use strict';

// ====================================================================
// 1. process.cwd() — getcwd() returns ENOSYS in proot
// ====================================================================
const _origCwd = process.cwd;
process.cwd = function() {
  try { return _origCwd.call(process); }
  catch(e) { return process.env.HOME || '/root'; }
};

// ====================================================================
// 2. os module patches — various /proc reads fail in proot
// ====================================================================
const _os = require('os');

// os.hostname() — may fail reading /proc/sys/kernel/hostname
const _origHostname = _os.hostname;
_os.hostname = function() {
  try { return _origHostname.call(_os); }
  catch(e) { return 'localhost'; }
};

// os.tmpdir() — ensure it returns /tmp
const _origTmpdir = _os.tmpdir;
_os.tmpdir = function() {
  try {
    const t = _origTmpdir.call(_os);
    return t || '/tmp';
  } catch(e) { return '/tmp'; }
};

// os.homedir() — may fail with ENOSYS
const _origHomedir = _os.homedir;
_os.homedir = function() {
  try { return _origHomedir.call(_os); }
  catch(e) { return process.env.HOME || '/root'; }
};

// os.userInfo() — getpwuid may fail in proot
const _origUserInfo = _os.userInfo;
_os.userInfo = function(opts) {
  try { return _origUserInfo.call(_os, opts); }
  catch(e) {
    return {
      uid: 0, gid: 0,
      username: 'root',
      homedir: process.env.HOME || '/root',
      shell: '/bin/bash'
    };
  }
};

// os.cpus() — reading /proc/cpuinfo may fail
const _origCpus = _os.cpus;
_os.cpus = function() {
  try {
    const cpus = _origCpus.call(_os);
    if (cpus && cpus.length > 0) return cpus;
  } catch(e) {}
  return [{ model: 'ARM', speed: 2000, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }];
};

// os.totalmem() / os.freemem() — reading /proc/meminfo may fail
const _origTotalmem = _os.totalmem;
_os.totalmem = function() {
  try { return _origTotalmem.call(_os); }
  catch(e) { return 4 * 1024 * 1024 * 1024; } // 4GB fallback
};
const _origFreemem = _os.freemem;
_os.freemem = function() {
  try { return _origFreemem.call(_os); }
  catch(e) { return 2 * 1024 * 1024 * 1024; } // 2GB fallback
};

// os.networkInterfaces() — Android blocks getifaddrs()
const _origNetIf = _os.networkInterfaces;
_os.networkInterfaces = function() {
  try {
    const ifaces = _origNetIf.call(_os);
    if (ifaces && Object.keys(ifaces).length > 0) return ifaces;
  } catch(e) {}
  return {
    lo: [{
      address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4',
      mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8'
    }]
  };
};

// ====================================================================
// 3. fs.mkdir — mkdirat() returns ENOSYS in proot
// ====================================================================
const _fs = require('fs');
const _path = require('path');
const _origMkdirSync = _fs.mkdirSync;
_fs.mkdirSync = function(p, options) {
  try {
    return _origMkdirSync.call(_fs, p, options);
  } catch(e) {
    if (e.code === 'ENOSYS' || (e.code === 'ENOENT' && options && options.recursive)) {
      const parts = _path.resolve(String(p)).split(_path.sep).filter(Boolean);
      let current = '';
      for (const part of parts) {
        current += _path.sep + part;
        try { _origMkdirSync.call(_fs, current); }
        catch(e2) { if (e2.code !== 'EEXIST' && e2.code !== 'EISDIR') { /* skip */ } }
      }
      return undefined;
    }
    throw e;
  }
};
const _origMkdir = _fs.mkdir;
_fs.mkdir = function(p, options, cb) {
  if (typeof options === 'function') { cb = options; options = undefined; }
  try { _fs.mkdirSync(p, options); if (cb) cb(null); }
  catch(e) { if (cb) cb(e); else throw e; }
};
const _fsp = _fs.promises;
if (_fsp) {
  const _origMkdirP = _fsp.mkdir;
  _fsp.mkdir = async function(p, options) {
    try { return await _origMkdirP.call(_fsp, p, options); }
    catch(e) {
      if (e.code === 'ENOSYS' || (e.code === 'ENOENT' && options && options.recursive)) {
        _fs.mkdirSync(p, options); return undefined;
      }
      throw e;
    }
  };
}

// ====================================================================
// 4. fs.rename — renameat2() may ENOSYS in proot; fallback to copy+unlink
// ====================================================================
const _origRenameSync = _fs.renameSync;
_fs.renameSync = function(oldPath, newPath) {
  try { return _origRenameSync.call(_fs, oldPath, newPath); }
  catch(e) {
    if (e.code === 'ENOSYS' || e.code === 'EXDEV') {
      _fs.copyFileSync(oldPath, newPath);
      try { _fs.unlinkSync(oldPath); } catch(_) {}
      return;
    }
    throw e;
  }
};
const _origRename = _fs.rename;
_fs.rename = function(oldPath, newPath, cb) {
  _origRename.call(_fs, oldPath, newPath, function(err) {
    if (err && (err.code === 'ENOSYS' || err.code === 'EXDEV')) {
      try {
        _fs.copyFileSync(oldPath, newPath);
        try { _fs.unlinkSync(oldPath); } catch(_) {}
        if (cb) cb(null);
      } catch(e2) { if (cb) cb(e2); }
    } else { if (cb) cb(err); }
  });
};
if (_fsp) {
  const _origRenameP = _fsp.rename;
  _fsp.rename = async function(oldPath, newPath) {
    try { return await _origRenameP.call(_fsp, oldPath, newPath); }
    catch(e) {
      if (e.code === 'ENOSYS' || e.code === 'EXDEV') {
        await _fsp.copyFile(oldPath, newPath);
        try { await _fsp.unlink(oldPath); } catch(_) {}
        return;
      }
      throw e;
    }
  };
}

// ====================================================================
// 5. fs.chmod/chown — fchmodat/fchownat may fail; tolerate ENOSYS
// ====================================================================
for (const fn of ['chmod', 'chown', 'lchown']) {
  const origSync = _fs[fn + 'Sync'];
  if (origSync) {
    _fs[fn + 'Sync'] = function() {
      try { return origSync.apply(_fs, arguments); }
      catch(e) { if (e.code === 'ENOSYS') return; throw e; }
    };
  }
  const origAsync = _fs[fn];
  if (origAsync) {
    _fs[fn] = function() {
      const args = Array.from(arguments);
      const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
      try { origSync.apply(_fs, args); if (cb) cb(null); }
      catch(e) { if (e.code === 'ENOSYS') { if (cb) cb(null); } else { if (cb) cb(e); else throw e; } }
    };
  }
}

// ====================================================================
// 6. fs.watch — inotify may fail; provide silent no-op fallback
// ====================================================================
const _origWatch = _fs.watch;
_fs.watch = function(filename, options, listener) {
  try { return _origWatch.call(_fs, filename, options, listener); }
  catch(e) {
    if (e.code === 'ENOSYS' || e.code === 'ENOSPC' || e.code === 'ENOENT') {
      // Return a fake watcher that does nothing
      const EventEmitter = require('events');
      const fake = new EventEmitter();
      fake.close = function() {};
      fake.ref = function() { return this; };
      fake.unref = function() { return this; };
      return fake;
    }
    throw e;
  }
};

// ====================================================================
// 7. child_process.spawn — handle ENOSYS (proot) and ENOENT (missing binary).
//    Command-aware mock:
//    - Side-effect cmds (git, node-gyp, cmake, make): return FAILURE (128)
//      so npm doesn't look for files they were supposed to create
//    - Everything else: return SUCCESS (0) — npm internals proceed
//    Handles both ENOSYS (proot syscall fail) and ENOENT (binary not found,
//    e.g. git not installed in rootfs).
// ====================================================================
const _cp = require('child_process');
const _EventEmitter = require('events');

// Commands that produce side effects (files). Must return failure.
// Note: node-gyp, make, cmake are NOT mocked — python3/make/g++ are
// installed in the rootfs so native addon compilation works properly.
function _isSideEffectCmd(cmd) {
  const base = String(cmd).split('/').pop();
  return base === 'git' || base === 'cmake';
}

// Should this error be mocked? ENOSYS always, ENOENT for side-effect cmds.
function _shouldMock(errCode, cmd) {
  if (errCode === 'ENOSYS') return true;
  if (errCode === 'ENOENT' && _isSideEffectCmd(cmd)) return true;
  return false;
}

function _makeFakeChild(exitCode) {
  const fake = new _EventEmitter();
  fake.stdout = new (require('stream').Readable)({ read() { this.push(null); } });
  fake.stderr = new (require('stream').Readable)({ read() { this.push(null); } });
  fake.stdin = new (require('stream').Writable)({ write(c,e,cb) { cb(); } });
  fake.pid = 0;
  fake.exitCode = null;
  fake.kill = function() { return false; };
  fake.ref = function() { return this; };
  fake.unref = function() { return this; };
  fake.connected = false;
  fake.disconnect = function() {};
  process.nextTick(() => {
    fake.exitCode = exitCode;
    fake.emit('close', exitCode, null);
  });
  return fake;
}

function _makeFakeSyncResult(code) {
  return { status: code, signal: null, stdout: Buffer.alloc(0),
           stderr: Buffer.alloc(0),
           pid: 0, output: [null, Buffer.alloc(0), Buffer.alloc(0)],
           error: null };
}

const _origSpawn = _cp.spawn;
_cp.spawn = function(cmd, args, options) {
  try {
    const child = _origSpawn.call(_cp, cmd, args, options);
    child.on('error', (err) => {
      if (_shouldMock(err.code, cmd)) {
        const code = _isSideEffectCmd(cmd) ? 128 : 0;
        child.emit('close', code, null);
      }
    });
    return child;
  } catch(e) {
    if (_shouldMock(e.code, cmd)) {
      return _makeFakeChild(_isSideEffectCmd(cmd) ? 128 : 0);
    }
    throw e;
  }
};
const _origSpawnSync = _cp.spawnSync;
_cp.spawnSync = function(cmd, args, options) {
  try {
    const r = _origSpawnSync.call(_cp, cmd, args, options);
    if (r.error && _shouldMock(r.error.code, cmd)) {
      return _makeFakeSyncResult(_isSideEffectCmd(cmd) ? 128 : 0);
    }
    return r;
  } catch(e) {
    if (_shouldMock(e.code, cmd)) {
      return _makeFakeSyncResult(_isSideEffectCmd(cmd) ? 128 : 0);
    }
    throw e;
  }
};
// Also patch exec/execFile which are wrappers around spawn
const _origExecFile = _cp.execFile;
_cp.execFile = function(file, args, options, cb) {
  if (typeof args === 'function') { cb = args; args = []; options = {}; }
  if (typeof options === 'function') { cb = options; options = {}; }
  try { return _origExecFile.call(_cp, file, args, options, cb); }
  catch(e) {
    if (_shouldMock(e.code, file)) {
      const code = _isSideEffectCmd(file) ? 128 : 0;
      if (cb) cb(code ? Object.assign(new Error('spawn failed'), {code:e.code}) : null, '', '');
      return;
    }
    throw e;
  }
};
const _origExecFileSync = _cp.execFileSync;
_cp.execFileSync = function(file, args, options) {
  try { return _origExecFileSync.call(_cp, file, args, options); }
  catch(e) {
    if (_shouldMock(e.code, file)) {
      if (_isSideEffectCmd(file)) throw e;
      return Buffer.alloc(0);
    }
    throw e;
  }
};
""".trimIndent()
        File(bypassDir, "proot-compat.js").writeText(prootCompatContent)

        // 4. Bionic bypass — comprehensive runtime patcher for openclaw.
        //    Loaded via NODE_OPTIONS="--require /root/.openclaw/bionic-bypass.js"
        val bypassContent = """
// OpenClaw Bionic Bypass - Auto-generated
// Comprehensive runtime compatibility layer for proot on Android 10+.
// Loaded via NODE_OPTIONS before any application code runs.

// Load all proot compatibility patches (shared with node-wrapper.js)
require('/root/.openclaw/proot-compat.js');
""".trimIndent()

        File(bypassDir, "bionic-bypass.js").writeText(bypassContent)

        val hookContent = """
// OpenClaw Fetch Hook - Auto-generated
// Logs request and response payloads from inside the gateway Node.js runtime.

'use strict';

const originalFetch = global.fetch;

if (typeof originalFetch === 'function') {
  global.fetch = async function(...args) {
    const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || String(args[0]));
    const opts = args[1] || {};
    const is8066Chat = url && /:8066\/v1\/chat\/completions(?:\?|$)/.test(String(url));
    let reqDebugForErr = null;

    if (is8066Chat) {
      console.log('agentclaw REQ_8066_CHAT_COMPLETIONS url=' + url);
      try {
        if (typeof opts.body === 'string' && opts.body.trim().startsWith('{')) {
          const reqJson = JSON.parse(opts.body);
          // 兼容旧版 OpenAI 接口：后端要求 messages[].content 为 string，
          // 但上游可能传数组（多段文本/多模态结构）。这里统一降级为纯文本。
          if (Array.isArray(reqJson?.messages)) {
            let normalized = false;
            reqJson.messages = reqJson.messages.map((m) => {
              if (!m || !Array.isArray(m.content)) return m;
              normalized = true;
              const text = m.content.map((part) => {
                if (typeof part === 'string') return part;
                if (part && typeof part === 'object') {
                  if (typeof part.text === 'string') return part.text;
                  if (typeof part.content === 'string') return part.content;
                  return JSON.stringify(part);
                }
                return String(part ?? '');
              }).join('\n');
              return { ...m, content: text };
            });
            if (normalized) {
              opts.body = JSON.stringify(reqJson);
              console.log('agentclaw REQ_8066_CHAT_NORMALIZED content=array->string');
            }
          }

          // clawbootdo 兼容模式：禁用工具调用，避免模型只返回 <tool_call> 文本后结束。
          // 部分 OpenAI-compat 后端不会执行工具编排，导致对话看起来“只生成一次就停”。
          let toolCompatApplied = false;
          if (Array.isArray(reqJson?.tools) && reqJson.tools.length > 0) {
            delete reqJson.tools;
            toolCompatApplied = true;
          }
          if (reqJson?.tool_choice !== undefined) {
            reqJson.tool_choice = 'none';
            toolCompatApplied = true;
          }
          if (Array.isArray(reqJson?.messages)) {
            const sysIndex = reqJson.messages.findIndex((m) => String(m?.role || '') === 'system');
            const noToolTip = '\n\n[兼容模式] 当前上游不支持工具调用执行，请直接输出最终答案，不要输出<tool_call>或工具调用JSON。';
            if (sysIndex >= 0 && typeof reqJson.messages[sysIndex]?.content === 'string' && !reqJson.messages[sysIndex].content.includes('兼容模式')) {
              reqJson.messages[sysIndex].content += noToolTip;
              toolCompatApplied = true;
            }
          }
          if (toolCompatApplied) {
            opts.body = JSON.stringify(reqJson);
            console.log('agentclaw REQ_8066_CHAT_TOOL_COMPAT tools-disabled');
          }

          const msgs = Array.isArray(reqJson?.messages) ? reqJson.messages : [];
          const roleStat = msgs.reduce((acc, m) => {
            const r = String(m?.role || 'unknown');
            acc[r] = (acc[r] || 0) + 1;
            return acc;
          }, {});
          const lastUser = [...msgs].reverse().find(m => String(m?.role || '') === 'user');
          const reqSummary = {
            model: reqJson?.model,
            stream: reqJson?.stream,
            messageCount: Array.isArray(reqJson?.messages) ? reqJson.messages.length : 0,
            temperature: reqJson?.temperature,
            roles: roleStat
          };
          reqDebugForErr = {
            url,
            method: opts?.method || 'POST',
            summary: reqSummary,
            headers: opts?.headers || null,
            bodyPreview: JSON.stringify(reqJson).slice(0, 800)
          };
          console.log('agentclaw REQ_8066_CHAT_SUMMARY ' + JSON.stringify(reqSummary));
          if (lastUser && typeof lastUser.content === 'string') {
            console.log('agentclaw REQ_8066_CHAT_LAST_USER ' + lastUser.content.slice(0, 300));
          }
          console.log('agentclaw REQ_8066_CHAT_BODY ' + JSON.stringify(reqJson).slice(0, 2000));
        } else {
          reqDebugForErr = {
            url,
            method: opts?.method || 'POST',
            headers: opts?.headers || null,
            bodyPreview: String(opts.body).slice(0, 800)
          };
          console.log('agentclaw REQ_8066_CHAT_BODY_RAW ' + String(opts.body).slice(0, 2000));
        }
      } catch (err) {
        console.log('agentclaw REQ_8066_CHAT_PARSE_ERR ' + (err?.message || String(err)));
        reqDebugForErr = {
          url,
          method: opts?.method || 'POST',
          headers: opts?.headers || null,
          bodyPreview: typeof opts?.body === 'string' ? opts.body.slice(0, 800) : String(opts?.body),
          parseError: err?.message || String(err)
        };
      }
    }

    console.log('\n\x1b[36m==========[OpenClaw 发出请求] ==========\x1b[0m');
    console.log('\x1b[33mURL:\x1b[0m', url);

    if (opts.body) {
      try {
        const parsedBody = JSON.parse(opts.body);
        console.log('\x1b[33mBody:\x1b[0m', JSON.stringify(parsedBody, null, 2));
      } catch (_err) {
        console.log('\x1b[33mBody:\x1b[0m', opts.body);
      }
    }

    let res;
    try {
      res = await originalFetch(...args);
    } catch (err) {
      if (is8066Chat) {
        const cause = err?.cause || {};
        if (reqDebugForErr) {
          // 单独打一行，便于仅按 "agentclaw" 过滤时快速看到请求参数摘要
          console.error('agentclaw RESP_8066_CHAT_FETCH_REQ ' + JSON.stringify(reqDebugForErr));
        }
        const detail = {
          message: err?.message || String(err),
          name: err?.name,
          code: err?.code || cause?.code,
          errno: err?.errno || cause?.errno,
          syscall: err?.syscall || cause?.syscall,
          address: err?.address || cause?.address,
          port: err?.port || cause?.port,
          causeMessage: cause?.message,
          request: reqDebugForErr
        };
        console.error('agentclaw RESP_8066_CHAT_FETCH_ERR ' + JSON.stringify(detail));
      }
      throw err;
    }
    const clone = typeof res.clone === 'function' ? res.clone() : res;

    if (is8066Chat) {
      console.log('agentclaw RESP_8066_CHAT_STATUS status=' + clone.status);
    }

    console.log('\n\x1b[32m========== [OpenClaw 收到响应] ==========\x1b[0m');
    console.log('\x1b[33mStatus:\x1b[0m', clone.status);

    (async () => {
      try {
        if (clone.body && typeof clone.body.getReader === 'function') {
          const reader = clone.body.getReader();
          const decoder = new TextDecoder('utf-8');
          let streamBuffer = '';
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            const chunk = decoder.decode(value, { stream: true });
            process.stdout.write(chunk);
            if (is8066Chat) {
              streamBuffer += chunk;
              if (streamBuffer.length > 4000) {
                streamBuffer = streamBuffer.slice(streamBuffer.length - 4000);
              }
            }
          }
          if (is8066Chat) {
            console.log('agentclaw RESP_8066_CHAT_STREAM ' + streamBuffer.slice(0, 2000));
          }
          console.log('\n\x1b[32m========== [响应流结束] ==========\x1b[0m\n');
        } else if (typeof clone.text === 'function') {
          const text = await clone.text();
          console.log(text);
          if (is8066Chat) {
            console.log('agentclaw RESP_8066_CHAT_TEXT ' + text.slice(0, 2000));
          }
          console.log('\n\x1b[32m========== [响应结束] ==========\x1b[0m\n');
        }
      } catch (err) {
        if (is8066Chat) {
          console.error('agentclaw RESP_8066_CHAT_READ_ERR', err?.message || err);
        }
        console.error('\x1b[31m[日志读取错误]\x1b[0m', err);
      }
    })();

    return res;
  };
}
""".trimIndent()
        File(bypassDir, "hook.js").writeText(hookContent)

        // 5. Git config — write .gitconfig directly to rootfs to avoid shell
        //    quoting issues when running `git config` inside proot via bash -c.
        //    Rewrites SSH URLs to HTTPS (no SSH keys in proot).
        //    npm dependencies like @whiskeysockets/libsignal-node use git+ssh.
        val gitConfig = File("$rootfsDir/root/.gitconfig")
        gitConfig.writeText(
            "[url \"https://github.com/\"]\n" +
            "\tinsteadOf = ssh://git@github.com/\n" +
            "\tinsteadOf = git@github.com:\n" +
            "[advice]\n" +
            "\tdetachedHead = false\n"
        )

        // Patch .bashrc
        val bashrc = File("$rootfsDir/root/.bashrc")
        val exportLine = "export NODE_OPTIONS=\"--require /root/.openclaw/bionic-bypass.js --require /root/.openclaw/hook.js\""

        val existing = if (bashrc.exists()) bashrc.readText() else ""
        if (!existing.contains("bionic-bypass")) {
            bashrc.appendText("\n# OpenClaw Bionic Bypass\n$exportLine\n")
        }
    }

    /**
     * Read DNS servers from Android's active network. Falls back to
     * Google DNS (8.8.8.8, 8.8.4.4) if system DNS is unavailable (#60).
     */
    private fun getSystemDnsServers(): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val network = cm.activeNetwork
                if (network != null) {
                    val linkProps: LinkProperties? = cm.getLinkProperties(network)
                    val dnsServers = linkProps?.dnsServers
                    if (dnsServers != null && dnsServers.isNotEmpty()) {
                        val lines = dnsServers.joinToString("\n") { "nameserver ${it.hostAddress}" }
                        // Always append Google DNS as fallback
                        return "$lines\nnameserver 8.8.8.8\n"
                    }
                }
            }
        } catch (_: Exception) {}
        return "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
    }

    fun writeResolvConf() {
        val content = getSystemDnsServers()
        // Try context.filesDir first (Android-guaranteed), fall back to
        // string-based configDir. Always call mkdirs() unconditionally. (#40)
        try {
            val dir = File(context.filesDir, "config")
            dir.mkdirs()
            File(dir, "resolv.conf").writeText(content)
        } catch (_: Exception) {
            // Fallback: use the string-based path
            File(configDir).mkdirs()
            File(configDir, "resolv.conf").writeText(content)
        }

        // Also write directly into rootfs /etc/resolv.conf so DNS works
        // even if the bind-mount fails or hasn't been set up yet (#40).
        try {
            val rootfsResolv = File(rootfsDir, "etc/resolv.conf")
            rootfsResolv.parentFile?.mkdirs()
            rootfsResolv.writeText(content)
        } catch (_: Exception) {}
    }

    /** Read a file from inside the rootfs (e.g. /root/.openclaw/openclaw.json). */
    fun readRootfsFile(path: String): String? {
        val file = resolveRootfsPath(path, mustExist = true)
        return if (file.exists()) file.readText() else null
    }

    fun readRootfsBytes(path: String): ByteArray? {
        val file = resolveRootfsPath(path, mustExist = true)
        return if (file.exists() && file.isFile) file.readBytes() else null
    }

    /** Write content to a file inside the rootfs, creating parent dirs as needed. */
    fun writeRootfsFile(path: String, content: String) {
        val file = resolveRootfsPath(path, mustExist = false)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun writeRootfsBytes(path: String, bytes: ByteArray) {
        val file = resolveRootfsPath(path, mustExist = false)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun listRootfsDir(path: String): List<Map<String, Any?>> {
        val dir = resolveRootfsPath(path, mustExist = true)
        if (!dir.exists()) {
            throw IllegalArgumentException("Path not found")
        }
        if (!dir.isDirectory) {
            throw IllegalArgumentException("Path is not a directory")
        }

        return dir.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.map { rootfsEntryToMap(it, dir) }
            ?: emptyList()
    }

    fun statRootfsPath(path: String): Map<String, Any?>? {
        val file = resolveRootfsPath(path, mustExist = true)
        if (!file.exists()) {
            return null
        }
        return rootfsEntryToMap(file, null)
    }

    fun deleteRootfsPath(path: String, recursive: Boolean) {
        if (path.isBlank()) {
            throw IllegalArgumentException("Refusing to delete rootfs root")
        }
        val file = resolveRootfsPath(path, mustExist = true)
        if (!file.exists()) {
            throw IllegalArgumentException("Path not found")
        }
        if (file.isDirectory && recursive) {
            deleteRecursively(file)
            return
        }
        if (!file.delete()) {
            throw IllegalStateException("Failed to delete path")
        }
    }

    fun mkdirRootfsPath(path: String, recursive: Boolean) {
        val dir = resolveRootfsPath(path, mustExist = false)
        val created = if (recursive) dir.mkdirs() else dir.mkdir()
        if (!created && !dir.exists()) {
            throw IllegalStateException("Failed to create directory")
        }
    }

    private fun resolveRootfsPath(path: String, mustExist: Boolean): File {
        val normalized = normalizeRelativePath(path)
        val root = File(rootfsDir).canonicalFile
        val candidate = if (normalized.isEmpty()) root else File(root, normalized)
        val normalizedCandidate = candidate.toPath().normalize()
        if (!normalizedCandidate.startsWith(root.toPath())) {
            throw IllegalArgumentException("Path escapes rootfs")
        }

        val resolved = if (mustExist && candidate.exists()) {
            candidate.canonicalFile
        } else {
            candidate.absoluteFile
        }
        if (!resolved.toPath().normalize().startsWith(root.toPath())) {
            throw IllegalArgumentException("Path escapes rootfs")
        }
        return resolved
    }

    private fun normalizeRelativePath(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(trimmed)) {
            throw IllegalArgumentException("Only relative paths are allowed")
        }

        val parts = mutableListOf<String>()
        for (segment in trimmed.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") {
                if (parts.isEmpty()) {
                    throw IllegalArgumentException("Path escapes rootfs")
                }
                parts.removeAt(parts.lastIndex)
            } else {
                parts.add(segment)
            }
        }
        return parts.joinToString(File.separator)
    }

    private fun rootfsEntryToMap(file: File, parent: File?): Map<String, Any?> {
        val root = File(rootfsDir).canonicalFile
        val relativePath = if (parent == null) {
            file.relativeTo(root).path
        } else {
            file.relativeTo(parent).path
        }.replace(File.separatorChar, '/')
        val type = when {
            java.nio.file.Files.isSymbolicLink(file.toPath()) -> "symlink"
            file.isDirectory -> "directory"
            else -> "file"
        }

        return mapOf(
            "name" to file.name,
            "path" to relativePath,
            "type" to type,
            "size" to if (file.isFile) file.length() else null,
            "modifiedAt" to Instant.ofEpochMilli(file.lastModified()).toString(),
        )
    }

    /**
     * Create fake /proc and /sys files that are bind-mounted into proot.
     * Android restricts access to many /proc entries; proot-distro works
     * around this by providing static fake data. We replicate that approach.
     */
    fun setupFakeSysdata() {
        val procDir = File("$configDir/proc_fakes")
        val sysDir = File("$configDir/sys_fakes")
        procDir.mkdirs()
        sysDir.mkdirs()

        // /proc/loadavg
        File(procDir, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")

        // /proc/stat — matching proot-distro (8 CPUs)
        File(procDir, "stat").writeText(
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
            "cpu0 31 0 226 12027 82 10 4 9 0 0\n" +
            "cpu1 45 0 290 11498 21 9 8 7 0 0\n" +
            "cpu2 52 0 401 11730 36 15 6 10 0 0\n" +
            "cpu3 42 0 268 11677 31 12 5 8 0 0\n" +
            "cpu4 789 0 720 11364 26 100 83 18 0 0\n" +
            "cpu5 486 0 438 11685 42 86 60 13 0 0\n" +
            "cpu6 314 0 336 11808 45 68 52 11 0 0\n" +
            "cpu7 198 0 198 11491 25 42 36 11 0 0\n" +
            "intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n" +
            "ctxt 38014093\n" +
            "btime 1694292441\n" +
            "processes 26442\n" +
            "procs_running 1\n" +
            "procs_blocked 0\n" +
            "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n"
        )

        // /proc/uptime
        File(procDir, "uptime").writeText("124.08 932.80\n")

        // /proc/version — fake kernel info matching proot-distro v4.37.0
        File(procDir, "version").writeText(
            "Linux version ${ProcessManager.FAKE_KERNEL_RELEASE} (proot@termux) " +
            "(gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) " +
            "${ProcessManager.FAKE_KERNEL_VERSION}\n"
        )

        // /proc/vmstat — matching proot-distro format
        File(procDir, "vmstat").writeText(
            "nr_free_pages 1743136\n" +
            "nr_zone_inactive_anon 179281\n" +
            "nr_zone_active_anon 7183\n" +
            "nr_zone_inactive_file 22858\n" +
            "nr_zone_active_file 51328\n" +
            "nr_zone_unevictable 642\n" +
            "nr_zone_write_pending 0\n" +
            "nr_mlock 0\n" +
            "nr_slab_reclaimable 7520\n" +
            "nr_slab_unreclaimable 10776\n" +
            "pgpgin 198292\n" +
            "pgpgout 7674\n" +
            "pswpin 0\n" +
            "pswpout 0\n" +
            "pgalloc_dma 0\n" +
            "pgalloc_dma32 0\n" +
            "pgalloc_normal 44669136\n" +
            "pgfree 46674674\n" +
            "pgactivate 1085674\n" +
            "pgdeactivate 340776\n" +
            "pglazyfree 139872\n" +
            "pgfault 37291463\n" +
            "pgmajfault 6854\n" +
            "pgrefill 480634\n"
        )

        // /proc/sys/kernel/cap_last_cap
        File(procDir, "cap_last_cap").writeText("40\n")

        // /proc/sys/fs/inotify/max_user_watches
        File(procDir, "max_user_watches").writeText("4096\n")

        // /proc/sys/crypto/fips_enabled — libgcrypt reads this on startup;
        // missing/unreadable on Android causes apt HTTP method to SIGABRT
        File(procDir, "fips_enabled").writeText("0\n")

        // Empty file for /sys/fs/selinux bind
        File(sysDir, "empty").writeText("")
    }

    private fun checkNodeInProot(): Boolean {
        return try {
            val pm = ProcessManager(filesDir, nativeLibDir)
            val output = pm.runInProotSync("node --version")
            output.trim().startsWith("v")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkOpenClawInProot(): Boolean {
        return try {
            val pm = ProcessManager(filesDir, nativeLibDir)
            val output = pm.runInProotSync("command -v openclaw")
            output.trim().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
