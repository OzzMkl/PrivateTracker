package org.privatetracker.server.web.page

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceStatus
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.model.Platform
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The web app's words, in the Spanish of the phone app.

private val SPANISH: Locale = Locale.forLanguageTag("es-MX")
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", SPANISH)
private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", SPANISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", SPANISH)

fun dateTime(instant: Instant, zone: ZoneId): String = DATE_TIME.format(instant.atZone(zone))
fun date(instant: Instant, zone: ZoneId): String = DATE.format(instant.atZone(zone))
fun time(instant: Instant, zone: ZoneId): String = TIME.format(instant.atZone(zone))

/** "hace 5 min", like the phone's lists. */
fun relativeTime(instant: Instant?, now: Instant): String {
    if (instant == null) return "nunca"
    val elapsed = Duration.between(instant, now)
    return when {
        elapsed < Duration.ofMinutes(1) -> "hace un momento"
        elapsed < Duration.ofHours(1) -> "hace ${elapsed.toMinutes()} min"
        elapsed < Duration.ofDays(1) -> "hace ${elapsed.toHours()} h"
        elapsed < Duration.ofDays(2) -> "ayer"
        else -> "hace ${elapsed.toDays()} días"
    }
}

fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.ONLINE -> "En línea"
    DeviceStatus.STALE -> "Inactivo"
    DeviceStatus.OFFLINE -> "Sin conexión"
}

fun approvalLabel(approval: DeviceApproval): String = when (approval) {
    DeviceApproval.PENDING -> "Pendiente"
    DeviceApproval.APPROVED -> "Aprobado"
    DeviceApproval.REJECTED -> "Rechazado"
}

fun platformLabel(platform: Platform): String = when (platform) {
    Platform.ANDROID -> "Android"
    Platform.LINUX -> "Linux"
    Platform.WEB -> "Web"
    Platform.OTHER -> "Otro"
}

fun transitionLabel(transition: GeofenceTransition): String = when (transition) {
    GeofenceTransition.ENTER -> "Entró"
    GeofenceTransition.EXIT -> "Salió"
}

/** "850 m", "12.4 km". */
fun distance(meters: Double): String = if (meters < 1_000) "${meters.toInt()} m" else String.format(SPANISH, "%.1f km", meters / 1_000)

fun count(n: Int, one: String, many: String): String = "${String.format(SPANISH, "%,d", n)} ${if (n == 1) one else many}"

/** What a field's broken rule means to whoever filled the form. */
fun violationText(violation: FieldViolation): String = when (violation.field to violation.rule) {
    "name" to FieldViolation.REQUIRED -> "Escribe un nombre."
    "name" to FieldViolation.TOO_LONG -> "El nombre es demasiado largo."
    "radius" to FieldViolation.OUT_OF_RANGE -> "El radio debe estar entre 50 m y 50 km."
    "center" to FieldViolation.INVALID_FORMAT -> "Toca el mapa para poner el centro."
    "vertices" to FieldViolation.OUT_OF_RANGE -> "Pon entre 3 y 50 puntos, en un área donde quepa un círculo de 50 m."
    "vertices" to FieldViolation.INVALID_FORMAT -> "Los lados del polígono no pueden cruzarse."
    "devices" to FieldViolation.REQUIRED -> "Elige al menos un dispositivo."
    "password" to FieldViolation.TOO_SHORT -> "La contraseña nueva debe tener al menos 15 caracteres. Una frase de varias palabras sirve."
    "password" to FieldViolation.TOO_LONG -> "La contraseña nueva es demasiado larga."
    "password" to FieldViolation.TOO_COMMON -> "Esa contraseña es de las primeras que alguien probaría. Elige otra."
    else -> "Revisa el campo ${violation.field}."
}
