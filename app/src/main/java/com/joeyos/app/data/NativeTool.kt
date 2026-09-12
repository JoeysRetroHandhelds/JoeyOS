package com.joeyos.app.data

import java.io.File
import kotlin.concurrent.thread

/**
 * Runs a bundled command-line tool, the one place the conversion tools share.
 *
 * A native executable (chdman today) is shipped as a library and run at arm's length; the
 * mechanics of running one - streaming its output a line at a
 * time for progress, and stopping it promptly when the user cancels - are the same for
 * all three, so they live here rather than being copied into each.
 *
 * Cancel is handled by a watcher that kills the process, not by watching its output: a
 * tool that prints nothing while it works (Azahar does not report progress) would
 * otherwise be uninterruptible, since the read below blocks until the process ends.
 */
object NativeTool {

    /**
     * Runs [binary] with [args], feeding each line of its output to [onLine] as it
     * arrives, and destroying it if [shouldStop] turns true. Returns true only on a clean
     * exit that was not cancelled.
     */
    fun run(
        binary: File,
        args: List<String>,
        shouldStop: () -> Boolean = { false },
        onLine: (String) -> Unit = {},
    ): Boolean = runCatching {
        val process = ProcessBuilder(listOf(binary.absolutePath) + args)
            .redirectErrorStream(true)
            .start()

        val watcher = thread(isDaemon = true) {
            while (process.isAlive) {
                if (shouldStop()) {
                    runCatching { process.destroy() }
                    break
                }
                Thread.sleep(200)
            }
        }

        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(1024)
            val line = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                for (i in 0 until read) {
                    val c = buffer[i]
                    if (c == '\r' || c == '\n') {
                        onLine(line.toString())
                        line.setLength(0)
                    } else {
                        line.append(c)
                    }
                }
            }
        }

        val ok = process.waitFor() == 0
        watcher.join(500)
        // A cancelled run is not a success even if the process happened to exit 0: its
        // output is incomplete, and the caller cleans up the half-written file.
        ok && !shouldStop()
    }.getOrDefault(false)
}
