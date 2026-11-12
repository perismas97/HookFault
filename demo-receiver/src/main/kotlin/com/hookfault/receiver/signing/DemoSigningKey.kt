package com.hookfault.receiver.signing

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val DEMO_KEY_BYTES = 32
private const val DEMO_KEY_FILE = "HOOKFAULT_DEMO_SIGNING_KEY_FILE"
private const val UNREADABLE_KEY = "$DEMO_KEY_FILE must point to a readable demo signing key file."

@Component
class DemoSigningKey(environment: Environment) {
    private val bytes = environment.getProperty(DEMO_KEY_FILE)?.let(::readKey)

    fun requireBytes(): ByteArray =
        checkNotNull(bytes) { "Demo signing key is not configured." }.copyOf()

    private fun readKey(file: String): ByteArray {
        check(file.isNotBlank()) { UNREADABLE_KEY }
        val key = try {
            val path = Path.of(file)
            check(Files.isRegularFile(path)) { UNREADABLE_KEY }
            Files.newInputStream(path).use { it.readNBytes(DEMO_KEY_BYTES + 1) }
        } catch (_: IOException) {
            throw IllegalStateException(UNREADABLE_KEY)
        } catch (_: InvalidPathException) {
            throw IllegalStateException(UNREADABLE_KEY)
        } catch (_: SecurityException) {
            throw IllegalStateException(UNREADABLE_KEY)
        }
        check(key.size == DEMO_KEY_BYTES) { "Demo signing key file must contain exactly $DEMO_KEY_BYTES bytes." }
        return key
    }
}
