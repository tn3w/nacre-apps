package org.nacre.clock

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.nacre.core.NacreTheme

data class Ringing(
    val uri: Uri,
    val title: String,
    val ringtone: String?,
    val vibrate: Boolean
) {
    val id get() = uri.schemeSpecificPart.toInt()
    val isAlarm get() = uri.scheme == "alarm"
    val actions get() = if (isAlarm) {
        listOf("Snooze" to RingService.SNOOZE, "Dismiss" to RingService.DISMISS)
    } else {
        listOf("+1:00" to RingService.ADD_MINUTE, "Stop" to RingService.DISMISS)
    }
}

class RingService : Service() {
    private val scope = MainScope()
    private var player: MediaPlayer? = null
    private var sound: Job? = null
    private val queue = ArrayDeque<Uri>()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        val action = intent?.action
        if (uri != null) {
            when (action) {
                null -> ring(uri)
                SILENCE -> drop(uri)
                else -> answer(action, uri)
            }
        }
        if (current.value == null) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        silence()
        scope.cancel()
        current.value = null
    }

    private fun answer(action: String, uri: Uri) {
        val ringing = current.value?.takeIf { it.uri == uri } ?: return
        when (action) {
            SNOOZE -> Store.snooze(ringing.id)
            ADD_MINUTE -> Store.addMinute(ringing.id)
            DISMISS -> if (!ringing.isAlarm) Store.resetTimer(ringing.id)
        }
        next()
    }

    private fun drop(uri: Uri) {
        if (current.value?.uri == uri) next() else queue.remove(uri)
    }

    private fun ring(uri: Uri) {
        val active = current.value
        if (active != null && active.uri != uri) {
            if (uri !in queue) queue.addLast(uri)
            return showForeground(active)
        }

        val ringing = describe(uri) ?: return
        silence()
        current.value = ringing
        showForeground(ringing)
        if (ringing.vibrate) vibrate()
        sound = scope.launch {
            launch { play(ringing.ringtone) }
            val minutes = Store[Option.Silence]
            if (minutes == 0) return@launch
            delay(minutes * 60_000L)
            next()
            if (current.value == null) stop()
        }
    }

    private fun next() {
        silence()
        current.value = null
        while (current.value == null) ring(queue.removeFirstOrNull() ?: return)
    }

    private fun describe(uri: Uri): Ringing? {
        val id = uri.schemeSpecificPart.toIntOrNull() ?: return null
        if (uri.scheme == "timer") {
            val timer = Store.timer(id) ?: return null
            return Ringing(uri, timer.label.ifEmpty { "Time's up" }, null, true)
        }
        val alarm = Store.alarm(id) ?: return null
        val title = alarm.label.ifEmpty { "Alarm" }
        return Ringing(uri, title, alarm.ringtone, alarm.vibrate)
    }

    private suspend fun play(ringtone: String?) {
        val candidates =
            listOfNotNull(
                ringtone?.toUri(),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                "android.resource://$packageName/${R.raw.fallback_alarm}".toUri()
            )
        val player = candidates.firstNotNullOfOrNull(::open) ?: return
        this.player = player
        player.setVolume(0f, 0f)
        player.start()
        val steps = maxOf(1, Store[Option.Ramp] * 10)
        for (step in 1..steps) {
            val volume = step.toFloat() / steps
            player.setVolume(volume * volume, volume * volume)
            delay(100)
        }
    }

    private fun open(uri: Uri): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(this, uri)
            player.isLooping = true
            player.prepare()
            player
        } catch (exception: Exception) {
            player.release()
            null
        }
    }

    private fun vibrate() {
        vibrator().vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

    private fun vibrator() = getSystemService(VibratorManager::class.java).defaultVibrator

    private fun silence() {
        sound?.cancel()
        player?.release()
        player = null
        vibrator().cancel()
    }

    private fun stop() {
        current.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showForeground(ringing: Ringing) {
        val type = FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        startForeground(NOTIFICATION, notification(ringing), type)
    }

    private fun notification(ringing: Ringing): Notification {
        val screen = PendingIntent.getActivity(
            this,
            0,
            Intent(
                this,
                RingActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val icon = if (ringing.isAlarm) R.drawable.ic_alarm else R.drawable.ic_timer
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(ringing.title)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
        ringing.actions.forEach { (label, action) ->
            val intent = PendingIntent.getService(
                this,
                0,
                command(this, action, ringing.uri),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, icon),
                    label,
                    intent
                )
                    .build()
            )
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL = "ringing"
        const val SNOOZE = "snooze"
        const val DISMISS = "dismiss"
        const val ADD_MINUTE = "add_minute"
        private const val SILENCE = "silence"
        private const val NOTIFICATION = 1

        val current = MutableStateFlow<Ringing?>(null)

        fun start(context: Context, uri: Uri) {
            context.startForegroundService(
                Intent(context, RingService::class.java).setData(uri)
            )
        }

        fun silence(context: Context, uri: String) {
            if (current.value != null) send(context, SILENCE, uri.toUri())
        }

        fun send(context: Context, action: String, uri: Uri) {
            context.startService(command(context, action, uri))
        }

        private fun command(context: Context, action: String, uri: Uri) =
            Intent(context, RingService::class.java).setAction(action).setData(uri)
    }
}

class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NacreTheme {
                val ringing by RingService.current.collectAsState()
                val current = ringing
                if (current == null) {
                    LaunchedEffect(Unit) { finish() }
                    return@NacreTheme
                }
                RingScreen(current) { RingService.send(this, it, current.uri) }
            }
        }
    }
}

@Composable
private fun RingScreen(ringing: Ringing, onAction: (String) -> Unit) {
    val now by rememberNow()
    val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        .format(timeFormatter())

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(time, fontSize = 88.sp, fontWeight = FontWeight.Light)
            Text(ringing.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(96.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val (secondary, primary) = ringing.actions
                OutlinedButton(
                    onClick = { onAction(secondary.second) },
                    modifier = Modifier.weight(1f).height(72.dp)
                ) {
                    Text(secondary.first, style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    onClick = { onAction(primary.second) },
                    modifier = Modifier.weight(1f).height(72.dp)
                ) {
                    Text(primary.first, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
