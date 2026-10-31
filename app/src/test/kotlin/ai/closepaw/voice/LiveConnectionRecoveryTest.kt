package ai.closepaw.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveConnectionRecoveryTest {
    @Test fun `temporary disconnect recovers without ending voice`() = runTest {
        val failures = mutableListOf<String>()
        val recovery = LiveConnectionRecovery(this, failures::add)
        recovery.disconnected()
        advanceTimeBy(5_000)
        recovery.connected()
        advanceTimeBy(20_000)
        assertThat(failures).isEmpty()
    }

    @Test fun `repeated disconnected events do not postpone a persistent failure`() = runTest {
        val failures = mutableListOf<String>()
        val recovery = LiveConnectionRecovery(this, failures::add)
        recovery.disconnected()
        advanceTimeBy(8_000)
        recovery.disconnected()
        advanceTimeBy(4_000)
        runCurrent()
        assertThat(failures).hasSize(1)
        assertThat(failures.single()).contains("network connection")
        recovery.failed("another failure")
        assertThat(failures).hasSize(1)
    }

    @Test fun `new disconnect gets a fresh recovery window after reconnect`() = runTest {
        val failures = mutableListOf<String>()
        val recovery = LiveConnectionRecovery(this, failures::add)
        recovery.disconnected()
        advanceTimeBy(8_000)
        recovery.connected()
        recovery.disconnected()
        advanceTimeBy(5_000)
        assertThat(failures).isEmpty()
        advanceTimeBy(7_000)
        runCurrent()
        assertThat(failures).hasSize(1)
    }

    @Test fun `hangup suppresses delayed and native close failures`() = runTest {
        val failures = mutableListOf<String>()
        val recovery = LiveConnectionRecovery(this, failures::add)
        recovery.disconnected()
        runCurrent()
        recovery.close()
        recovery.failed("closed")
        advanceTimeBy(20_000)
        assertThat(failures).isEmpty()
    }
}
