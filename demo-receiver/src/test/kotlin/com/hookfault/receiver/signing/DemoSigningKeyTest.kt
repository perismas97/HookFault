package com.hookfault.receiver.signing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path

class DemoSigningKeyTest {
    @TempDir
    lateinit var directory: Path

    private val contextRunner = ApplicationContextRunner().withUserConfiguration(DemoSigningKey::class.java)

    @Test
    fun doesNotFallBackToADefaultKey() {
        val key = DemoSigningKey(MockEnvironment())

        assertThatThrownBy { key.requireBytes() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Demo signing key is not configured.")
    }

    @Test
    fun loadsExactBinaryBytesAndKeepsAnIndependentSnapshot() {
        val expected = ByteArray(32) { (it * 17).toByte() }
        val file = Files.write(directory.resolve("demo-signing-key"), expected)

        contextRunner.withPropertyValues("HOOKFAULT_DEMO_SIGNING_KEY_FILE=$file").run { context ->
            assertThat(context).hasNotFailed()
            val key = context.getBean(DemoSigningKey::class.java)
            val copy = key.requireBytes()
            assertThat(copy).isEqualTo(expected)
            copy.fill(0)
            Files.write(file, ByteArray(32))
            assertThat(key.requireBytes()).isEqualTo(expected)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 31, 33])
    fun rejectsInvalidKeySizesAtStartup(size: Int) {
        val file = Files.write(directory.resolve("demo-signing-key"), ByteArray(size) { 'x'.code.toByte() })

        contextRunner.withPropertyValues("HOOKFAULT_DEMO_SIGNING_KEY_FILE=$file").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("Demo signing key file must contain exactly 32 bytes.")
            assertThat(context.startupFailure!!.stackTraceToString()).doesNotContain(file.toString(), "xxxxxxxx")
        }
    }

    @Test
    fun rejectsMissingFilesWithoutReportingTheirPath() {
        val file = directory.resolve("missing-demo-signing-key")

        contextRunner.withPropertyValues("HOOKFAULT_DEMO_SIGNING_KEY_FILE=$file").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage(
                "HOOKFAULT_DEMO_SIGNING_KEY_FILE must point to a readable demo signing key file.",
            )
            assertThat(context.startupFailure!!.stackTraceToString()).doesNotContain(file.toString())
        }
    }

    @Test
    fun rejectsAnExplicitlyBlankFileSetting() {
        contextRunner.withPropertyValues("HOOKFAULT_DEMO_SIGNING_KEY_FILE=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage(
                "HOOKFAULT_DEMO_SIGNING_KEY_FILE must point to a readable demo signing key file.",
            )
        }
    }
}
