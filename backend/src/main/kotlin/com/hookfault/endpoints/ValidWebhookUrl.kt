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
        if (value.length > ENDPOINT_URL_MAX_LENGTH) return false

        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return false
        }

        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.isOpaque &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }
}
