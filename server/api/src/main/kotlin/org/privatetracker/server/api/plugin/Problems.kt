package org.privatetracker.server.api.plugin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.dto.ProblemDetails

/**
 * The only way a route reports an error. Throwing (instead of responding) lets StatusPages
 * write the problem body and keeps its generic 404 handler from overwriting specific errors.
 */
class ApiException(
    val status: HttpStatusCode,
    val code: ErrorCode,
    val detail: String? = null,
    val retryAfterSeconds: Long? = null,
    val maxBatchSize: Int? = null,
) : RuntimeException(detail ?: code.name)

fun DomainError.toApiException(): ApiException = when (this) {
    DomainError.DeviceNotRegistered ->
        ApiException(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_REGISTERED, "Register the device with POST ${ApiV1.REGISTER}")
    DomainError.DeviceNotFound -> ApiException(HttpStatusCode.NotFound, ErrorCode.DEVICE_NOT_FOUND)
    DomainError.NewDevicesDisabled ->
        ApiException(HttpStatusCode.Forbidden, ErrorCode.NEW_DEVICES_DISABLED, "This server does not accept new devices")
    is DomainError.UnsupportedProtocolVersion ->
        ApiException(HttpStatusCode.UnprocessableEntity, ErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "Supported version: $supported")
    is DomainError.Validation -> ApiException(HttpStatusCode.UnprocessableEntity, ErrorCode.VALIDATION_FAILED, describe())
    is DomainError.BatchTooLarge -> ApiException(
        HttpStatusCode.PayloadTooLarge,
        ErrorCode.BATCH_TOO_LARGE,
        "At most $maxSize locations per batch",
        maxBatchSize = maxSize,
    )
    else -> ApiException(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR)
}

fun DomainError.Validation.describe(): String = violations.joinToString { "${it.field}: ${it.rule}" }

fun <T> Outcome<T>.getOrThrowApi(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw error.toApiException()
}

internal fun Application.installProblemResponses() {
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respondProblem(cause) }
        exception<BadRequestException> { call, cause ->
            call.respondProblem(ApiException(HttpStatusCode.BadRequest, ErrorCode.MALFORMED_JSON, cause.message))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respondProblem(ApiException(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondProblem(ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND))
        }
        // RateLimit has already set Retry-After; only the body is missing.
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respondProblem(ApiException(HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED, "Too many requests"))
        }
    }
}

private suspend fun ApplicationCall.respondProblem(problem: ApiException) {
    problem.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    val body = ProblemDetails(
        type = problem.code.problemType,
        title = problem.code.title(),
        status = problem.status.value,
        detail = problem.detail,
        code = problem.code.name,
        maxBatchSize = problem.maxBatchSize,
    )
    respondText(
        text = ProtocolJson.encodeToString(ProblemDetails.serializer(), body),
        contentType = ContentType.parse(ApiV1.PROBLEM_CONTENT_TYPE),
        status = problem.status,
    )
}

/** DEVICE_NOT_REGISTERED becomes "Device not registered". */
private fun ErrorCode.title(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
