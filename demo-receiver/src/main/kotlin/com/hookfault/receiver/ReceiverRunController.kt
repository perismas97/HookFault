package com.hookfault.receiver

import com.hookfault.receiver.events.ReceiverRunSnapshot
import com.hookfault.receiver.events.ReceiverRunState
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/demo/runs/{runId}")
internal class ReceiverRunController(private val state: ReceiverRunState) {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun inspect(@PathVariable("runId") runId: String): ResponseEntity<ReceiverRunSnapshot> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state.inspect(parseId(runId)))

    @DeleteMapping
    fun reset(@PathVariable("runId") runId: String): ResponseEntity<Void> {
        state.reset(parseId(runId))
        return ResponseEntity.noContent().build()
    }

    private fun parseId(value: String): UUID {
        val id = if (value.length == 36) runCatching { UUID.fromString(value) }.getOrNull() else null
        return id?.takeIf { it.toString().equals(value, ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a run ID in canonical UUID format.")
    }
}
