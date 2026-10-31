package com.hookfault.http

import java.time.Duration

internal const val MAX_JSON_REQUEST_BYTES = 16 * 1024
internal val WEBHOOK_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
internal val WEBHOOK_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
