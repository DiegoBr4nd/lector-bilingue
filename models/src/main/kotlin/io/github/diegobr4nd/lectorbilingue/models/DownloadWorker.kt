package io.github.diegobr4nd.lectorbilingue.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID

/**
 * Descarga e instala un modelo en segundo plano (WorkManager: el "cartero" de Android que reintenta los
 * trabajos aunque se cierre la app). Es un adaptador fino: toda la lógica está en [ModelDownloadJob].
 *
 * - Entrada: `modelId`. Progreso: `bytes`/`total`. Éxito: `pair`. Fallo: `error` = un código de
 *   [DownloadOutcome.CODES] (`integridad`, `firma`, `catalogo`, `politica`, `archivos`, `red`,
 *   `desconocido`); nunca el texto de una excepción.
 * - Corre como servicio en primer plano de tipo `dataSync` con una notificación genérica ("Descargando
 *   modelo en-es", sin URLs) y un botón Cancelar. Si Android no deja pasar a primer plano (app en segundo
 *   plano en Android 12+), la descarga sigue igual como trabajo normal. Sin permiso de notificaciones
 *   (Android 13+) también sigue: solo no se ve la notificación.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(DownloadWork.KEY_MODEL_ID)
        val notifications = DownloadNotifications(applicationContext)
        val notificationId = DownloadNotifications.notificationId(modelId)
        val inForeground = try {
            setForeground(notifications.foregroundInfo(notificationId, id, pair = null, downloaded = 0, total = 0))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+, app en segundo plano).
            false
        }

        val notificationGate = NotificationGate()
        val outcome = Models.downloadJob(applicationContext).run(modelId, runAttemptCount) { pair, downloaded, total ->
            setProgressAsync(workDataOf(DownloadWork.KEY_BYTES to downloaded, DownloadWork.KEY_TOTAL to total))
            if (inForeground && notificationGate.shouldUpdate(downloaded, total)) {
                setForegroundAsync(notifications.foregroundInfo(notificationId, id, pair, downloaded, total))
            }
        }
        return when (outcome) {
            is DownloadOutcome.Success -> Result.success(workDataOf(DownloadWork.KEY_PAIR to outcome.installed.pair))
            DownloadOutcome.Retry -> Result.retry()
            is DownloadOutcome.Failure -> Result.failure(workDataOf(DownloadWork.KEY_ERROR to outcome.code))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelId = inputData.getString(DownloadWork.KEY_MODEL_ID)
        return DownloadNotifications(applicationContext)
            .foregroundInfo(DownloadNotifications.notificationId(modelId), id, pair = null, downloaded = 0, total = 0)
    }
}

/**
 * Limita las actualizaciones de la notificación a una por segundo (Android penaliza más de ~5 por segundo).
 * Deja pasar la primera y la final (bytes == total), que mantiene el 100 %.
 */
internal class NotificationGate(
    private val minIntervalNanos: Long = 1_000_000_000L,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private var lastAt = 0L
    private var any = false

    fun shouldUpdate(downloaded: Long, total: Long): Boolean {
        val now = nanoClock()
        val final = total > 0 && downloaded >= total
        if (!any || final || now - lastAt >= minIntervalNanos) {
            any = true
            lastAt = now
            return true
        }
        return false
    }
}

/** Canal y notificación de descarga. El texto es genérico: el par de idiomas y el porcentaje, nada más. */
internal class DownloadNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "model-downloads"
        private const val BASE_ID = 0x4D00_0000

        /** Un id estable por modelo (dos descargas en cola no pisan su notificación). */
        fun notificationId(modelId: String?): Int = BASE_ID + ((modelId?.hashCode() ?: 0) and 0xFFFF)
    }

    fun foregroundInfo(notificationId: Int, workId: UUID, pair: String?, downloaded: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val notification = build(workId, pair, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /** Crear un canal que ya existe no hace nada (idempotente). */
    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun build(workId: UUID, pair: String?, downloaded: Long, total: Long): Notification {
        val title = if (pair == null) {
            context.getString(R.string.model_download_preparing)
        } else {
            context.getString(R.string.model_download_title, pair)
        }
        val percent = if (total > 0) ((downloaded.coerceIn(0, total) * 100) / total).toInt() else 0
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, percent, total <= 0)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    context.getString(R.string.model_download_cancel),
                    cancel,
                ).build(),
            )
        if (total > 0) builder.setContentText(context.getString(R.string.model_download_percent, percent))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
