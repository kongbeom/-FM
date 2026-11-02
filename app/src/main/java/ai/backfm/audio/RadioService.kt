package ai.backfm.audio

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.service.media.MediaBrowserService
import ai.backfm.BackfmApp
import ai.backfm.R
import ai.backfm.core.Episode
import ai.backfm.core.PlaybackRequestState
import ai.backfm.data.Codec
import ai.backfm.data.Record
import ai.backfm.ui.MainActivity
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

data class PlaybackStatus(
    val episodeId: String? = null, val playing: Boolean = false, val preparing: Boolean = false,
    val position: Int = 0, val duration: Int = 0, val audioPath: String? = null,
    val error: String = "", val section: String = "방송 준비", val playWhenReady: Boolean = false
)

class RadioService : MediaBrowserService() {
    companion object {
        const val PREPARE = "ai.backfm.PREPARE"
        const val PLAY = "ai.backfm.PLAY"
        const val PAUSE = "ai.backfm.PAUSE"
        const val RESTART = "ai.backfm.RESTART"
        const val STOP = "ai.backfm.STOP"
        const val NAVIGATION = "ai.backfm.NAVIGATION"
        const val SAMPLE = "ai.backfm.SAMPLE"
        private val mutableStatus = MutableStateFlow(PlaybackStatus())
        val status = mutableStatus.asStateFlow()
        private val commandToken = java.util.UUID.randomUUID().toString()
        fun commandIntent(context: Context, action: String) = Intent(context, RadioService::class.java).setAction(action).putExtra("command_token", commandToken)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as BackfmApp
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var player: MediaPlayer? = null
    private var episode: Episode? = null
    private var rendering: Job? = null
    private var ticker: Job? = null
    private var foreground = false
    private var audioAllowed = false
    private var suspended = false
    private var demoInterruption = false
    private val requests = PlaybackRequestState()
    private var pendingSeek: Int? = null
    private var navigationJob: Job? = null
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { pauseByUser() } }
    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pauseByUser()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { suspended = player?.isPlaying == true; pause(abandon = false) }
                    AudioManager.AUDIOFOCUS_GAIN -> if (suspended && audioAllowed && requests.wantsPlay) { suspended = false; startReadyPlayback() }
                }
            }, Handler(Looper.getMainLooper())).build()
        session = MediaSession(this, "BACKFM").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pauseByUser()
                override fun onStop() = stop()
                override fun onSkipToPrevious() = restart()
                override fun onSkipToNext() = stop()
                override fun onSeekTo(pos: Long) = seek(pos)
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId != null) beginRequest(mediaId) {
                        withContext(Dispatchers.IO) { app.database.records().read("episode").firstOrNull { it.id == mediaId }?.let(Codec::episode) }
                    }
                }
            })
            setSessionActivity(PendingIntent.getActivity(this@RadioService, 0, Intent(this@RadioService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        sessionToken = session.sessionToken
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("radio", "BACKFM 방송 재생", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        scope.launch { app.settings.flow.collect { settings -> audioAllowed = settings.consent && settings.voice; if (!audioAllowed) stop() } }
        scope.launch { app.database.records().observe().collect { notifyChildrenChanged("episodes") } }
        publish()
    }
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val trusted = clientUid == android.os.Process.myUid() || clientUid == 1000 || packageManager.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", clientPackageName) == PackageManager.PERMISSION_GRANTED
        return if (trusted) BrowserRoot("podcasts", Bundle().apply { putString("content_type", "podcast") }) else null
    }
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.detach()
        scope.launch {
            val items = mutableListOf<MediaBrowser.MediaItem>()
            if (parentId == "podcasts") items += MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("episodes").setTitle("BACKFM · 팟캐스트").setSubtitle("직접 보관한 귀갓길 에피소드").build(), MediaBrowser.MediaItem.FLAG_BROWSABLE)
            if (parentId == "episodes" && audioAllowed) {
                val saved = withContext(Dispatchers.IO) { app.database.records().read("episode").map(Codec::episode) }
                saved.sortedByDescending { it.createdAt }.forEach { e -> items += MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(e.id).setTitle(e.title).setSubtitle(if (e.source == ai.backfm.core.DataSource.DEMO) "DEMO · 가상 여정" else "내 기기의 여행 기억").build(), MediaBrowser.MediaItem.FLAG_PLAYABLE) }
            }
            result.sendResult(items)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("command_token") != commandToken) return START_NOT_STICKY
        when (intent?.action) {
            PREPARE -> intent.getStringExtra("episode")?.let { json ->
                runCatching { Codec.episode(Record("episode", "", json)) }.onSuccess { requestPrepare(it) }.onFailure { stop() }
            }
            PLAY -> play()
            PAUSE -> pauseByUser()
            STOP -> stop()
            RESTART -> restart()
            NAVIGATION -> if (!app.safety.automotive) simulateNavigation()
            SAMPLE -> {
                val sample = File(cacheDir, "fixed-demo.wav")
                assets.open("fixed-demo.wav").use { input -> sample.outputStream().use { input.copyTo(it) } }
                val line = ai.backfm.core.ScriptLine("고정 샘플 · 현재 응답 미반영", assets.open("fixed-demo-script.txt").bufferedReader().use { it.readText() }, setOf("demo:fixed"))
                requestPrepare(Episode("fixed-sample", "fixed-demo", "고정 시연 샘플 · 현재 응답 미반영", 0, 60, listOf(line), ai.backfm.core.DataSource.DEMO, audioPath = sample.absolutePath))
            }
        }
        return START_NOT_STICKY
    }
    private fun requestPrepare(next: Episode) {
        beginRequest(next.id) { next }
    }
    private fun beginRequest(id: String, load: suspend () -> Episode?) {
        val token = requests.begin()
        releasePlayback(releaseForeground = false)
        mutableStatus.value = PlaybackStatus(episodeId = id, preparing = true, section = "오프라인 한국어 음성 준비 중", playWhenReady = true)
        promote()
        rendering = scope.launch {
            var preparingPlayer: MediaPlayer? = null
            try {
                val settings = app.settings.flow.first()
                ensureActive()
                if (!requests.isCurrent(token)) return@launch
                audioAllowed = settings.consent && settings.voice
                if (!audioAllowed) { stop(); return@launch }
                val next = load() ?: error("보관한 방송을 찾을 수 없습니다.")
                ensureActive()
                if (!requests.isCurrent(token)) return@launch
                episode = next
                val saved = next.audioPath?.let(::File)?.takeIf { it.exists() }
                val destination = saved ?: File(cacheDir, "speech/${next.id}.wav")
                val file = if (destination.exists()) destination else app.audioRendererFactory(this@RadioService).render(next.script, destination)
                ensureActive()
                if (!requests.isCurrent(token)) return@launch
                val media = MediaPlayer().also { preparingPlayer = it }
                media.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                media.setDataSource(file.absolutePath)
                withContext(Dispatchers.IO) { media.prepare() }
                ensureActive()
                if (!requests.isCurrent(token)) return@launch
                player = media
                preparingPlayer = null
                media.setOnSeekCompleteListener { if (player === media) { pendingSeek = null; publish() } }
                media.setOnCompletionListener { if (player === media && requests.isCurrent(token)) complete() }
                media.setOnErrorListener { _, _, _ -> if (player === media && requests.isCurrent(token)) fail("음성 파일을 재생할 수 없습니다. 다시 합성해 주세요."); true }
                mutableStatus.value = PlaybackStatus(next.id, duration = media.duration, audioPath = file.absolutePath, section = next.lines.first().section)
                session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, next.id).putString(MediaMetadata.METADATA_KEY_TITLE, next.title).putString(MediaMetadata.METADATA_KEY_ARTIST, "BACKFM · Podcast").putLong(MediaMetadata.METADATA_KEY_DURATION, media.duration.toLong()).build())
                if (next.saved && next.audioPath == null) withContext(Dispatchers.IO) { app.dataMutex.withLock {
                    if (!app.settings.flow.first().consent || app.database.records().read("episode").none { it.id == next.id }) return@withLock
                    val permanent = File(filesDir, "episodes/${next.id}.wav").apply { parentFile?.mkdirs() }
                    file.copyTo(permanent, true)
                    if (app.database.records().read("episode").any { it.id == next.id } && app.settings.flow.first().consent)
                        app.database.records().put(Codec.episode(next.copy(audioPath = permanent.absolutePath)))
                } }
                ensureActive()
                if (requests.shouldPlay(token)) startReadyPlayback() else publish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (requests.isCurrent(token)) fail(e.message?.take(180) ?: "음성 합성 실패 · 다시 시도해 주세요.") }
            finally { preparingPlayer?.release() }
        }
    }
    private fun play() {
        if (mutableStatus.value.episodeId == null) return
        requests.resume()
        if (mutableStatus.value.preparing) { promote(); publish(); return }
        startReadyPlayback()
    }
    private fun startReadyPlayback(fromStart: Boolean = false) {
        if (!requests.wantsPlay) return
        if (!audioAllowed || demoInterruption) return
        val media = player ?: return
        val position = if (fromStart || media.currentPosition >= media.duration) 0 else pendingSeek ?: media.currentPosition
        if (!checkRideTime(media.duration, position)) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { pause(); mutableStatus.value = mutableStatus.value.copy(error = "다른 오디오가 우선입니다. 잠시 후 다시 재생해 주세요."); return }
        if (!checkRideTime(media.duration, position)) return
        if (position == 0 && (fromStart || media.currentPosition >= media.duration)) { pendingSeek = 0; media.seekTo(0) }
        mutableStatus.value = mutableStatus.value.copy(error = "")
        media.start(); promote(); publish()
        ticker?.cancel()
        ticker = scope.launch { while (isActive && player?.isPlaying == true) {
            if (!checkRideTime(media.duration, pendingSeek ?: media.currentPosition)) break
            publish(); delay(400)
        } }
    }
    private fun checkRideTime(duration: Int, position: Int): Boolean {
        if (app.rideTime.fits(duration, position)) return true
        requests.pause(); pause()
        mutableStatus.value = mutableStatus.value.copy(error = "음성의 남은 길이가 현재 남은 이동시간보다 깁니다. 짧은 방송을 선택하거나 도착 후 들어주세요.", playWhenReady = false)
        publish()
        return false
    }
    private fun seek(pos: Long) {
        val media = player ?: return
        val position = pos.coerceIn(0, media.duration.toLong()).toInt()
        if (media.isPlaying && !checkRideTime(media.duration, position)) return
        pendingSeek = position; media.seekTo(position); publish()
    }
    private fun pause(abandon: Boolean = true) {
        player?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel(); publish()
        if (abandon) { suspended = false; audio.abandonAudioFocusRequest(focus) }
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
    }
    private fun pauseByUser() { requests.pause(); navigationJob?.cancel(); demoInterruption = false; pause() }
    private fun restart() { if (mutableStatus.value.preparing) play() else { requests.resume(); if (player != null) startReadyPlayback(fromStart = true) else publish() } }
    private fun complete() { requests.pause(); pause(); mutableStatus.value = mutableStatus.value.copy(position = mutableStatus.value.duration, section = "방송 종료"); publish() }
    private fun stop(releaseForeground: Boolean = true) {
        requests.stop(); releasePlayback(releaseForeground)
    }
    private fun releasePlayback(releaseForeground: Boolean = true) {
        navigationJob?.cancel(); navigationJob = null
        rendering?.cancel(); rendering = null; ticker?.cancel(); suspended = false; demoInterruption = false
        player?.release(); player = null; episode = null; pendingSeek = null
        audio.abandonAudioFocusRequest(focus)
        mutableStatus.value = PlaybackStatus(); publish()
        if (releaseForeground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; getSystemService(NotificationManager::class.java).cancel(1) }
    }
    private fun fail(message: String) {
        requests.pause()
        player?.release(); player = null; ticker?.cancel(); audio.abandonAudioFocusRequest(focus)
        mutableStatus.value = mutableStatus.value.copy(playing = false, preparing = false, error = message, section = "음성 준비 실패 · 대본 이용 가능")
        publish(); stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
    }
    private fun simulateNavigation() {
        if (demoInterruption) return
        val resumeAfter = player?.isPlaying == true
        pause(); demoInterruption = true
        mutableStatus.value = mutableStatus.value.copy(section = "DEMO · 내비 안내 우선 / 일시정지")
        navigationJob = scope.launch { delay(3500); demoInterruption = false; if (resumeAfter && audioAllowed && requests.wantsPlay) startReadyPlayback() }
    }
    private fun publish() {
        val media = player
        val old = mutableStatus.value
        val position = pendingSeek ?: media?.currentPosition ?: old.position
        val playing = media?.isPlaying == true
        val totalChars = episode?.lines?.sumOf { it.text.length } ?: 0
        var running = 0
        val progressChar = if (old.duration > 0) position.toFloat() / old.duration * totalChars else 0f
        val section = episode?.lines?.firstOrNull { running += it.text.length; running >= progressChar }?.section ?: old.section
        mutableStatus.value = old.copy(playing = playing, position = position, playWhenReady = requests.wantsPlay, section = if (old.preparing || old.error.isNotBlank() || demoInterruption) old.section else section)
        val playbackState = when { old.preparing -> if (requests.wantsPlay) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED; old.error.isNotBlank() -> PlaybackState.STATE_ERROR; playing -> PlaybackState.STATE_PLAYING; media != null -> PlaybackState.STATE_PAUSED; else -> PlaybackState.STATE_STOPPED }
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID).setState(playbackState, position.toLong(), if (playing) 1f else 0f).build())
        if (foreground) getSystemService(NotificationManager::class.java).notify(1, notification())
    }
    private fun promote() { startForeground(1, notification()); foreground = true }
    private fun notification(): Notification {
        fun action(label: String, value: String) = Notification.Action.Builder(R.drawable.ic_backfm, label, PendingIntent.getService(this, value.hashCode(), commandIntent(this, value), PendingIntent.FLAG_IMMUTABLE)).build()
        return Notification.Builder(this, "radio").setSmallIcon(R.drawable.ic_backfm).setContentTitle(episode?.title ?: "BACKFM").setContentText(mutableStatus.value.section)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(if (mutableStatus.value.playing || (mutableStatus.value.preparing && requests.wantsPlay)) "일시정지" else "재생", if (mutableStatus.value.playing || (mutableStatus.value.preparing && requests.wantsPlay)) PAUSE else PLAY))
            .addAction(action("종료", STOP)).setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(mutableStatus.value.playing || mutableStatus.value.preparing).build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!mutableStatus.value.playing && !mutableStatus.value.preparing) stopSelf() }
    override fun onDestroy() { stop(); unregisterReceiver(noisy); session.release(); scope.cancel(); super.onDestroy() }
}
