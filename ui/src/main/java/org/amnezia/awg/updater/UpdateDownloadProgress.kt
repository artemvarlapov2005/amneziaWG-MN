package org.amnezia.awg.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One shared progress stream, including downloads started by JobScheduler. */
class UpdateDownloadProgress {
    enum class Phase { CONNECTING, DOWNLOADING, VERIFYING }
    data class Value(val token: String, val artifact: String, val generation: Long, val phase: Phase,
                     val route: String = "", val attempt: Int = 0, val bytes: Long = 0, val total: Long = -1)
    private val mutable = MutableStateFlow<Value?>(null)
    val state = mutable.asStateFlow()
    @Synchronized fun begin(artifact: String, generation: Long): String {
        val token = UUID.randomUUID().toString()
        mutable.value = Value(token, artifact, generation, Phase.CONNECTING)
        return token
    }
    @Synchronized fun route(token: String, label: String, attempt: Int) {
        mutable.value?.takeIf { it.token == token }?.let { mutable.value = it.copy(phase = Phase.CONNECTING, route = label, attempt = attempt, bytes = 0, total = -1) }
    }
    @Synchronized fun bytes(token: String, bytes: Long, total: Long) {
        mutable.value?.takeIf { it.token == token }?.let { mutable.value = it.copy(phase = Phase.DOWNLOADING, bytes = bytes, total = total) }
    }
    @Synchronized fun verifying(token: String) {
        mutable.value?.takeIf { it.token == token }?.let { mutable.value = it.copy(phase = Phase.VERIFYING) }
    }
    @Synchronized fun finish(token: String) { if (mutable.value?.token == token) mutable.value = null }
}
