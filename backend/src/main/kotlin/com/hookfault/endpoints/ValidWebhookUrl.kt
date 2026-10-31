package com.hookfault.endpoints

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.net.URI
import java.net.URISyntaxException
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [WebhookUrlValidator::class])
annotation class ValidWebhookUrl(
    val message: String = "must be an absolute HTTP(S) URL without credentials or a fragment",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class WebhookUrlValidator : ConstraintValidator<ValidWebhookUrl, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        return parseWebhookUrl(value) != null
    }
}

internal fun parseWebhookUrl(value: String): URI? {
    if (value.length > ENDPOINT_URL_MAX_LENGTH) return null
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return null
    }

    return uri.takeIf {
        (it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true)) &&
            !it.isOpaque &&
            !it.host.isNullOrBlank() &&
            it.rawUserInfo == null &&
            it.rawFragment == null &&
            (it.port == -1 || it.port in 1..65535)
    }
}
