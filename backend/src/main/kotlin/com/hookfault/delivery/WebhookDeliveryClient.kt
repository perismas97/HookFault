package com.hookfault.delivery

import com.hookfault.http.WEBHOOK_REQUEST_TIMEOUT
import com.hookfault.http.WebhookRequestFactory
import com.hookfault.signing.SignedWebhookEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Component
class WebhookDeliveryClient internal constructor(
    private val client: HttpClient,
    private val requests: WebhookRequestFactory,
    private val timeout: Duration,
) {
    @Autowired
    constructor(client: HttpClient, requests: WebhookRequestFactory) : this(client, requests, WEBHOOK_REQUEST_TIMEOUT)

    init {
        require(!timeout.isNegative && !timeout.isZero) { "Webhook delivery timeout must be positive" }
    }

    @Throws(InterruptedException::class)
    fun deliver(url: String, event: SignedWebhookEvent): WebhookDeliveryResult {
        val request = requests.create(url, event)
        val subscriber = BoundedResponseSubscriber()
        val status = AtomicInteger()
        val started = System.nanoTime()
        val exchange = client.sendAsync(request) { info ->
            status.set(info.statusCode())
            subscriber
        }
        fun failure(reason: WebhookDeliveryFailure) =
            WebhookDeliveryResult.Failure(reason, status.get().takeIf { it != 0 })

        try {
            // Unlike a streaming BodyHandler, this future completes only after the bounded body is read.
            val remaining = timeout.toNanos() - (System.nanoTime() - started)
            if (remaining <= 0) throw TimeoutException()
            val response = exchange.get(remaining, NANOSECONDS)
            return WebhookDeliveryResult.Response(response.statusCode(), response.body())
        } catch (_: TimeoutException) {
            return failure(WebhookDeliveryFailure.TIMEOUT)
        } catch (error: ExecutionException) {
            var cause: Throwable = error
            while (cause is ExecutionException || cause is CompletionException) {
                cause = cause.cause ?: throw cause
            }
            return when (cause) {
                is HttpTimeoutException -> failure(WebhookDeliveryFailure.TIMEOUT)
                is ResponseBodyTooLargeException -> failure(WebhookDeliveryFailure.RESPONSE_TOO_LARGE)
                is IOException -> failure(WebhookDeliveryFailure.NETWORK_ERROR)
                else -> throw cause
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } finally {
            subscriber.cancel()
            exchange.cancel(true)
        }
    }
}
