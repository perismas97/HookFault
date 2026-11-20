package com.hookfault.delivery

import com.hookfault.http.MAX_WEBHOOK_RESPONSE_BYTES
import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

internal class ResponseBodyTooLargeException : RuntimeException("Webhook response exceeds the byte limit")

internal class BoundedResponseSubscriber : HttpResponse.BodySubscriber<ByteArray> {
    private val body = CompletableFuture<ByteArray>()
    private val buffer = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = body

    @Synchronized
    override fun onSubscribe(subscription: Flow.Subscription) {
        if (this.subscription != null || body.isDone) {
            subscription.cancel()
            return
        }
        this.subscription = subscription
        subscription.request(1)
    }

    @Synchronized
    override fun onNext(items: List<ByteBuffer>) {
        if (body.isDone) return
        for (item in items) {
            if (item.remaining() > MAX_WEBHOOK_RESPONSE_BYTES - buffer.size()) {
                body.completeExceptionally(ResponseBodyTooLargeException())
                subscription?.cancel()
                return
            }
            val bytes = ByteArray(item.remaining())
            item.get(bytes)
            buffer.writeBytes(bytes)
        }
        subscription?.request(1)
    }

    @Synchronized
    override fun onError(error: Throwable) {
        body.completeExceptionally(error)
    }

    @Synchronized
    override fun onComplete() {
        body.complete(buffer.toByteArray())
    }

    @Synchronized
    fun cancel() {
        if (!body.isDone) {
            body.completeExceptionally(CancellationException("Webhook response cancelled"))
            subscription?.cancel()
        }
    }
}
