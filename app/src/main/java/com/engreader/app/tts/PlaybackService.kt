package com.engreader.app.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

/**
 * Keeps read-aloud alive while the app is not on screen.
 *
 * Reading aloud is the one thing in the app that is meant to continue with the phone
 * in a pocket, and without a foreground service Android is free to kill the process
 * the moment the activity leaves the screen — the voice would stop mid-sentence for no
 * reason the reader can see. The service holds no voice of its own: that belongs to the
 * application-wide [Speaker], and this only keeps the process alive, says what is
 * playing, and puts play/pause/stop on the lock screen.
 *
 * The controls work through [Hooks], which [ListeningSession] registers once when the
 * container is built; nothing here reaches into the UI. Pausing keeps the session and
 * its notification alive — that is what makes the play button reappear — while stopping
 * tears the whole thing down, and the queue it belongs to goes with it.
 */
class PlaybackService : Service() {

    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * True while audio focus is only lost for a moment — a call, or a navigation prompt.
     *
     * [AudioManager.AUDIOFOCUS_GAIN] is the cue that focus is back and the reading should
     * carry on where it left off; without it a transient loss paused the reader for good,
     * and a hand on the phone was needed after every phone call. It also decides whether a
     * pause abandons the focus request: a pause the reader did not ask for has to keep the
     * request alive, because abandoning it takes this listener off the focus stack and the
     * `GAIN` that ends the call never arrives.
     */
    private var transientLoss: Boolean = false

    private var title: String = ""
    private var subtitle: String = ""
    private var playing: Boolean = false

    /** Set once [startForeground] has run, so a later update never has to start it again. */
    private var inForeground: Boolean = false

    private val audioManager: AudioManager
        get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "EngReader").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        // The reader asked for play, so they decide, not the focus stack.
                        transientLoss = false
                        Hooks.resume?.invoke()
                    }

                    override fun onPause() {
                        Hooks.pause?.invoke()
                    }

                    override fun onStop() {
                        Hooks.stop?.invoke()
                    }

                    override fun onSkipToNext() {
                        Hooks.next?.invoke()
                    }

                    override fun onSkipToPrevious() {
                        Hooks.previous?.invoke()
                    }
                },
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // The reader's own stop path is the only thing that knows to clear the
                // highlight and the queue, so the button goes through it. Focus goes with
                // the session instead of being held until the service is destroyed.
                transientLoss = false
                abandonFocus()
                Hooks.stop?.invoke()
                stopSelf()
            }

            ACTION_PAUSE -> control {
                // The reader asked for quiet, so the reader decides when it comes back:
                // the focus request is released, and a later GAIN is not a resume.
                transientLoss = false
                abandonFocus()
                Hooks.pause?.invoke()
            }

            ACTION_RESUME -> control {
                transientLoss = false
                Hooks.resume?.invoke()
            }

            ACTION_NEXT -> control {
                transientLoss = false
                Hooks.next?.invoke()
            }

            ACTION_PREVIOUS -> control {
                transientLoss = false
                Hooks.previous?.invoke()
            }

            ACTION_SET_PLAYING -> control {
                readExtras(intent)
                playing = intent.getBooleanExtra(EXTRA_PLAYING, playing)
                // A pause is also what the focus listener itself asks for, so only a
                // pause the reader caused releases the request: releasing it there would
                // mean the GAIN that ends a call never reached this listener.
                if (!playing && !transientLoss) abandonFocus()
                publish()
            }

            else -> {
                readExtras(intent)
                playing = true
                // Asked for here rather than in `onCreate`, which also runs for the
                // commands above. Those are only ever delivered to a service that is
                // already playing, so a service created to receive one is a stale
                // command being retired — and taking audio focus for it would silence
                // whatever the reader is actually listening to, a moment before
                // `control` shut this one down again.
                requestFocus()
                startForeground(NOTIFICATION_ID, notification())
                inForeground = true
                publish()
            }
        }
        // Not sticky: a restart would have no reader behind it and nothing to say.
        return START_NOT_STICKY
    }

    /**
     * Runs a command that only makes sense on an already-visible service.
     *
     * These arrive while the notification is up. On a service that was just created —
     * a stale command after the process was restarted — there is nothing to control and
     * nothing to show, so it goes away rather than sitting in the background waiting to
     * be killed.
     */
    private inline fun control(action: () -> Unit) {
        if (!inForeground) {
            stopSelf()
            return
        }
        action()
    }

    private fun readExtras(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
        intent?.getStringExtra(EXTRA_SUBTITLE)?.let { subtitle = it }
    }

    override fun onDestroy() {
        // Explicit rather than relying on the platform to take the notification down
        // with the service: this one is `setOngoing(true)`, and a foreground service
        // that goes away without clearing its own notification leaves a card the reader
        // cannot swipe away and that opens nothing.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        session?.isActive = false
        session?.release()
        session = null
        abandonFocus()
        super.onDestroy()
    }

    /** Rewrites the notification and the lock-screen state after a play/pause change. */
    private fun publish() {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                )
                .build(),
        )
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification()) }
    }

    /**
     * Asks for audio focus, so another app's music pauses instead of playing over the
     * voice. A refusal is not fatal: the reading still happens, it just shares the
     * speaker, which is better than going silent.
     */
    private fun requestFocus() {
        // Built once and reused for the life of the service. The platform identifies a
        // focus request by its listener, so a request built fresh on every start is a
        // different client from the one already on the stack — and asking for focus
        // again makes the framework hand `AUDIOFOCUS_LOSS` to the old one. That loss is
        // this service's own pause trigger, so every start after the first paused itself
        // a few milliseconds in: the voice never began, and the speaker button looked
        // dead from the second tap onwards. One request, re-requested, is a no-op
        // refresh of a client the framework already knows.
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    // Taken for good: a call, or another player taking over. The reading
                    // stops, and the pause it causes releases the focus request (the loss
                    // was not transient), so nothing keeps a dead client on the stack.
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        transientLoss = false
                        Hooks.pause?.invoke()
                    }

                    // A call or a navigation prompt: focus comes back, so the reading is
                    // asked to come back with it.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        transientLoss = true
                        Hooks.pause?.invoke()
                    }

                    // Focus is back. Resume only from a loss we paused for: if the reader
                    // pressed pause in the meantime, a resume here would start the voice
                    // under their hands. The flag is cleared by every one of their
                    // commands, and by the resume itself.
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (transientLoss) {
                            transientLoss = false
                            Hooks.resume?.invoke()
                        }
                    }

                    // A duck is the one change the reading is meant to survive, and any
                    // other code is nothing this service has a policy for.
                    else -> Unit
                }
            }
            .build()
            .also { focusRequest = it }
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun notification(): Notification {
        // The play/pause button is the middle one, which is the slot the compact view
        // shows; the two skips are what a listener reaches for with a pocketed phone.
        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(1)
        session?.let { style.setMediaSession(it.sessionToken) }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "正在朗读" })
            .setContentText(subtitle.ifBlank { "EngReader 正在为你朗读" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(style)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "上一句",
                    serviceIntent(ACTION_PREVIOUS, 0),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (playing) "暂停" else "继续",
                    serviceIntent(if (playing) ACTION_PAUSE else ACTION_RESUME, 1),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "下一句",
                    serviceIntent(ACTION_NEXT, 2),
                ).build(),
            )
            .addAction(Notification.Action.Builder(null, "停止", serviceIntent(ACTION_STOP, 3)).build())
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                // Tapping the notification returns to the reader. Resolved from the
                // package rather than a class reference so the service does not have to
                // know which activity is the launcher.
                packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@PlaybackService,
                            0,
                            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW).apply {
                description = "朗读文章时显示，用来暂停或停止"
                setShowBadge(false)
            },
        )
    }

    /**
     * What the service calls back into.
     *
     * Plain lambdas rather than a binder: [ListeningSession] registers them once when
     * the container is built, and they stay for the life of the process — the lock
     * screen has to keep working after every screen that could have owned them is gone.
     */
    object Hooks {
        var resume: (() -> Unit)? = null
        var pause: (() -> Unit)? = null
        var stop: (() -> Unit)? = null
        var next: (() -> Unit)? = null
        var previous: (() -> Unit)? = null
    }

    companion object {

        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 41

        private const val ACTION_START = "com.engreader.app.action.START_READING"
        private const val ACTION_STOP = "com.engreader.app.action.STOP_READING"
        private const val ACTION_PAUSE = "com.engreader.app.action.PAUSE_READING"
        private const val ACTION_RESUME = "com.engreader.app.action.RESUME_READING"
        private const val ACTION_NEXT = "com.engreader.app.action.NEXT_SENTENCE"
        private const val ACTION_PREVIOUS = "com.engreader.app.action.PREVIOUS_SENTENCE"
        private const val ACTION_SET_PLAYING = "com.engreader.app.action.SET_PLAYING"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_PLAYING = "playing"

        /** Brings the service up, or updates what it says when it is already running. */
        fun start(context: Context, title: String, subtitle: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
            runCatching { context.startForegroundService(intent) }
        }

        /**
         * Tells a running service whether the voice is currently going, for the buttons.
         *
         * Plain `startService`: the service is already in the foreground at this point —
         * that is what [setPlaying] reports — so it needs no promotion, and asking for
         * one would only risk the platform's five-second deadline if the service were
         * not there after all.
         */
        fun setPlaying(context: Context, playing: Boolean, title: String = "", subtitle: String = "") {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SET_PLAYING)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
