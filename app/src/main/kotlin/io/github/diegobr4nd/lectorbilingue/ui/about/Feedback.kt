package io.github.diegobr4nd.lectorbilingue.ui.about

import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import io.github.diegobr4nd.lectorbilingue.BuildConfig
import java.net.URLEncoder

/** Dirección pública para comentarios (spec 6a, decisión 3): un correo solo para la app, nunca el personal. */
const val FEEDBACK_EMAIL = "lectorbilingue.app@gmail.com"

/** Lo único del teléfono que va en el correo (spec 6a §5). Nada de libros, idiomas, RAM ni registros. */
data class DeviceInfo(
    val versionName: String,
    val flavor: String,
    val android: String,
    val manufacturer: String,
    val model: String,
)

data class FeedbackMail(val to: String, val subject: String, val body: String)

/**
 * El correo preparado. [subjectTemplate]: `%1$s` nombre de la app, `%2$s` versión. [bodyTemplate]: `%1$s` versión,
 * `%2$s` flavor, `%3$s` Android, `%4$s` fabricante, `%5$s` modelo (strings feedback_subject y feedback_body).
 */
fun feedbackMail(appName: String, subjectTemplate: String, bodyTemplate: String, info: DeviceInfo): FeedbackMail =
    FeedbackMail(
        to = FEEDBACK_EMAIL,
        subject = subjectTemplate.format(appName, info.versionName),
        body = bodyTemplate.format(info.versionName, info.flavor, info.android, info.manufacturer, info.model),
    )

/** `mailto:` con asunto y cuerpo codificados (RFC 6068: los espacios van como %20, no como "+"). */
fun mailtoUri(mail: FeedbackMail): String = "mailto:${mail.to}?subject=${encode(mail.subject)}&body=${encode(mail.body)}"

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/**
 * Abre la app de correo con el mensaje listo; la persona lo lee, lo cambia y decide si lo envía. La app no envía nada.
 * El asunto y el cuerpo van también como extras: algunas apps de correo ignoran los de la dirección mailto.
 */
internal fun feedbackIntent(mail: FeedbackMail): Intent =
    Intent(Intent.ACTION_SENDTO, mailtoUri(mail).toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.to))
        putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        putExtra(Intent.EXTRA_TEXT, mail.body)
    }

fun currentDeviceInfo(): DeviceInfo = DeviceInfo(
    versionName = BuildConfig.VERSION_NAME,
    flavor = BuildConfig.FLAVOR,
    android = Build.VERSION.RELEASE,
    manufacturer = Build.MANUFACTURER,
    model = Build.MODEL,
)
