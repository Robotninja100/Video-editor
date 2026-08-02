package nl.artifation.videoeditor.analysis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import nl.artifation.videoeditor.R

/**
 * Houdt de analyse draaiend terwijl het scherm uit staat.
 *
 * Analyse duurt minuten en mag niet sneuvelen zodra de gebruiker de app naar de
 * achtergrond stuurt — dat is precies wanneer je hem laat lopen. Een
 * foreground-service met een zichtbare melding is de enige manier waarop Android
 * dat toestaat.
 *
 * De service bevat zelf geen beslissingen. Wát er gebeurt, in welke volgorde en
 * of het toestel het aankan, staat in `:core-pipeline`, `:core-jobs` en
 * `:core-thermal`. Deze klasse levert alleen het proces waarin dat mag draaien.
 *
 * **Niet gecompileerd.** Zie README: `:app` staat nog niet in de build.
 */
public class AnalysisService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            melding(getString(R.string.analysis_notification_title)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        // TODO(fase 2): de wachtrij uit :core-jobs aandrijven, met de
        //  ThermalWorkGate uit :core-pipeline als rem. De service zelf blijft dun.
        return START_STICKY
    }

    private fun melding(tekst: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.analysis_channel_name),
                // Laag: dit is voortgang, geen gebeurtenis. Een hogere stand
                // laat het toestel trillen bij elke stap.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tekst)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "analyse"
        const val NOTIFICATION_ID = 1
    }
}
