package dev.cascam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

class BroadcastService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transmissão SportCam", NotificationManager.IMPORTANCE_LOW))
        val openSportCam = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("SportCam transmitindo")
            .setContentText("Câmera, microfone e envio continuam ativos com a tela apagada")
            .setContentIntent(openSportCam)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        // dataSync entra junto por causa do acesso remoto: com o site ligado o aparelho continua
        // atendendo o navegador e mantendo a sessão do túnel de pé mesmo antes de existir
        // transmissão, e sem esse tipo o serviço não teria motivo declarado para isso.
        startForeground(
            ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cascam:broadcast").also { it.acquire() }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "broadcast"; private const val ID = 1001 }
}
