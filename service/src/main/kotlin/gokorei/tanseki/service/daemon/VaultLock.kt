package gokorei.tanseki.service.daemon

import gokorei.tanseki.service.logging.Redaction
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Per-vault advisory lock enforcing a **single writer**. Acquiring it fails if
 * another daemon (this JVM or another process) already holds it.
 */
class VaultLock(private val lockFile: Path) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * `acquire`/`release`/`isHeld` read and replace the pair together, so they
     * share one monitor: two threads racing to start a daemon would otherwise
     * both see an unheld lock, each open its own channel, and each believe it
     * owns the vault while only one `FileLock` survives.
     */
    @get:Synchronized
    val isHeld: Boolean get() = lock?.isValid == true

    @Synchronized
    fun acquire(): VaultLock {
        if (isHeld) return this
        Files.createDirectories(lockFile.parent)
        val opened = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired =
            try {
                opened.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        if (acquired == null) {
            runCatching { opened.close() }
            // The lock file lives inside the user's vault; report only its name.
            error(
                "vault already locked (single writer): ${Redaction.fileName(lockFile.toString())}"
            )
        }
        channel = opened
        lock = acquired
        return this
    }

    @Synchronized
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
