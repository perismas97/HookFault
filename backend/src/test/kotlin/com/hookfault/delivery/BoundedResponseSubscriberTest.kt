package com.hookfault.delivery

import com.hookfault.http.MAX_WEBHOOK_RESPONSE_BYTES
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.Flow

class BoundedResponseSubscriberTest {
    @Test
    fun acceptsExactlyTheLimitAcrossReadOnlyBuffersAndBatches() {
        val subscriber = BoundedResponseSubscriber()
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        val bytes = ByteArray(MAX_WEBHOOK_RESPONSE_BYTES) { (it % 256).toByte() }
        subscriber.onNext(listOf(ByteBuffer.wrap(bytes, 0, 100).asReadOnlyBuffer(), ByteBuffer.allocate(0)))
        subscriber.onNext(listOf(ByteBuffer.wrap(bytes, 100, bytes.size - 100).asReadOnlyBuffer()))
        assertThat(subscriber.getBody().toCompletableFuture()).isNotDone()
        subscriber.onComplete()
        assertThat(subscriber.getBody().toCompletableFuture().join()).isEqualTo(bytes)
        assertThat(subscription.requests).isEqualTo(3)
        assertThat(subscription.cancelled).isFalse()
    }

    @Test
    fun acceptsAnEmptyResponse() {
        val subscriber = BoundedResponseSubscriber()
        subscriber.onSubscribe(Subscription())
        subscriber.onComplete()
        assertThat(subscriber.getBody().toCompletableFuture().join()).isEmpty()
    }

    @Test
    fun cancelsBeforeCopyingAnOversizedBuffer() {
        val subscriber = BoundedResponseSubscriber()
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        val bytes = ByteBuffer.allocate(MAX_WEBHOOK_RESPONSE_BYTES + 1)
        subscriber.onNext(listOf(bytes))
        assertThat(subscription.cancelled).isTrue()
        assertThat(bytes.position()).isZero()
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(ResponseBodyTooLargeException::class.java)
    }

    @Test
    fun countsBytesAcrossBuffersAndIgnoresDataAfterRejection() {
        val subscriber = BoundedResponseSubscriber()
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.allocate(MAX_WEBHOOK_RESPONSE_BYTES - 1)))
        subscriber.onNext(listOf(ByteBuffer.allocate(1), ByteBuffer.allocate(1)))
        subscriber.onNext(listOf(ByteBuffer.allocate(10)))
        subscriber.onComplete()
        assertThat(subscription.cancelled).isTrue()
        assertThat(subscription.requests).isEqualTo(2)
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(ResponseBodyTooLargeException::class.java)
    }

    @Test
    fun propagatesTransportErrorsWithoutReturningAPartialBody() {
        val subscriber = BoundedResponseSubscriber()
        subscriber.onSubscribe(Subscription())
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2))))
        subscriber.onError(IOException("test transport failure"))
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(IOException::class.java)
    }

    @Test
    fun cancelsASubscriptionThatArrivesAfterTheDeadline() {
        val subscriber = BoundedResponseSubscriber()
        subscriber.cancel()
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        assertThat(subscription.cancelled).isTrue()
        assertThat(subscription.requests).isZero()
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun cancelsAnActiveSubscriptionButDoesNotCancelAFinishedOne() {
        val subscriber = BoundedResponseSubscriber()
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.cancel()
        assertThat(subscription.cancelled).isTrue()

        val finished = BoundedResponseSubscriber()
        val finishedSubscription = Subscription()
        finished.onSubscribe(finishedSubscription)
        finished.onComplete()
        finished.cancel()
        assertThat(finishedSubscription.cancelled).isFalse()
    }

    @Test
    fun cancelsDuplicateSubscriptions() {
        val subscriber = BoundedResponseSubscriber()
        val original = Subscription()
        val duplicate = Subscription()
        subscriber.onSubscribe(original)
        subscriber.onSubscribe(duplicate)
        assertThat(original.cancelled).isFalse()
        assertThat(duplicate.cancelled).isTrue()
        subscriber.cancel()
    }

    private class Subscription : Flow.Subscription {
        var requests = 0
        var cancelled = false

        override fun request(n: Long) {
            assertThat(n).isEqualTo(1)
            requests++
        }

        override fun cancel() {
            cancelled = true
        }
    }
}
