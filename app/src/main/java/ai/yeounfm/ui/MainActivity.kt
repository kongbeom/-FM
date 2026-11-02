package ai.yeounfm.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.yeounfm.YeounFmApp
import ai.yeounfm.BuildConfig
import ai.yeounfm.R
import ai.yeounfm.audio.*
import ai.yeounfm.core.*
import ai.yeounfm.data.Codec
import ai.yeounfm.vehicle.PlatformJourneyProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen(val label: String) { HOME("홈"), JOURNEY("여행 기록"), PICK("기억 선택"), PREPARE("방송 준비"), PLAYER("라디오"), LIBRARY("지난 방송"), SETTINGS("설정") }

class MainActivity : ComponentActivity() {
    private val model: YeounFmViewModel by viewModels()
    private val app get() = application as YeounFmApp
    private var screen = Screen.HOME
    private var state = UiState()
    private var playback = PlaybackStatus()
    private var pickStep = 0
    private var nickname = ""
    private var selectedVisit = ""
    private var emotion = ""
    private var pickStarted = 0L
    private var requestedSeconds = 60
    private var scriptRevealed = false
    private var openedEpisodeId: String? = null
    private var activeDialog: AlertDialog? = null
    private var playerLabel: TextView? = null
    private var playerTime: TextView? = null
    private var playerProgress: ProgressBar? = null
    private var playButton: Button? = null
    private var restartButton: Button? = null
    private var samplePlayButton: Button? = null
    private var etaLabel: TextView? = null
    private var playerError: TextView? = null
    private data class ViewportKey(val screen: Screen, val step: Int, val participant: String, val locked: Boolean, val consent: Boolean, val wide: Boolean)
    private data class ViewportPosition(val contentY: Int, val menuY: Int, val tabsX: Int)
    private var renderedViewport: ViewportKey? = null
    private var pendingViewport: ViewportPosition? = null
    private var contentScroll: ScrollView? = null
    private var menuScroll: ScrollView? = null
    private var tabsScroll: HorizontalScrollView? = null
    private val bg = Color.rgb(16, 21, 18)
    private val cardBg = Color.rgb(28, 35, 30)
    private val accent = Color.rgb(197, 242, 119)
    private val muted = Color.rgb(162, 177, 164)
    private val white = Color.rgb(241, 246, 237)
    private val amber = Color.rgb(255, 207, 124)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.state.collect { next ->
                if (next.safety.locked && !state.safety.locked) { clearPick(); activeDialog?.dismiss(); activeDialog = null; scriptRevealed = false }
                if (next.episode?.id != openedEpisodeId) { scriptRevealed = false; openedEpisodeId = next.episode?.id }
                val previous = state
                state = next
                if (state.message.isNotBlank()) { Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show(); model.clearMessage() }
                if (previous.copy(message = "") != next.copy(message = "") || playerLabel == null) render()
            } }
            launch { RadioService.status.collect { val sampleChanged = (playback.episodeId == "fixed-sample") != (it.episodeId == "fixed-sample"); playback = it; if (sampleChanged && screen == Screen.PLAYER) render() else updatePlayer() } }
            launch { while (kotlinx.coroutines.currentCoroutineContext().isActive) { updateEta(); kotlinx.coroutines.delay(1000) } }
        } }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { clearPick(); go(Screen.HOME) }
        })
        render()
    }
    override fun onPause() { super.onPause(); if (screen == Screen.PICK) { clearPick(); render() } }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); clearPick(); activeDialog?.dismiss(); render() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43) {
            render()
            model.message(if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) "내비 권한이 허용되었습니다. 여행 기록에서 기록 연결을 재개해 주세요." else "내비 권한이 허용되지 않았습니다. Demo Mode 또는 수동 장소 추가를 이용할 수 있습니다.")
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 18f, color: Int = white, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5)); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun LinearLayout.space(size: Int = 12) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(size))) }
    private fun LinearLayout.copy(value: String, size: Float = 18f, color: Int = white, bold: Boolean = false) { addView(text(value, size, color, bold)) }
    private fun LinearLayout.button(label: String, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit): Button {
        val b = Button(this@MainActivity).apply {
            text = label; isAllCaps = false; textSize = 18f; minHeight = dp(60); minimumHeight = dp(60)
            setTextColor(if (primary) bg else white); background = shape(if (primary) accent else Color.rgb(47, 58, 49), 12)
            setPadding(dp(18), dp(10), dp(18), dp(10)); isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
            setOnClickListener { action() }
        }
        addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })
        return b
    }
    private fun LinearLayout.card(block: LinearLayout.() -> Unit) {
        addView(column().apply { background = shape(cardBg); setPadding(dp(22), dp(16), dp(22), dp(18)); block() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun LinearLayout.heading(kicker: String, title: String, subtitle: String) { copy(kicker, 14f, accent, true); copy(title, 32f, white, true); copy(subtitle, 18f, muted); space(18) }
    private fun go(target: Screen) { if (screen != target) { clearPick(); scriptRevealed = false }; screen = target; render() }
    private fun clearPick() { pickStep = 0; nickname = ""; selectedVisit = ""; emotion = ""; pickStarted = 0 }
    private fun render() {
        val wide = resources.configuration.screenWidthDp >= 840
        val key = ViewportKey(screen, if (screen == Screen.PICK) pickStep else 0,
            if (screen == Screen.PICK) nickname else "", state.safety.locked, state.settings.consent, wide)
        val previous = pendingViewport ?: ViewportPosition(contentScroll?.scrollY ?: 0, menuScroll?.scrollY ?: 0, tabsScroll?.scrollX ?: 0)
        val restore = previous.copy(contentY = if (renderedViewport == key) previous.contentY else 0)
        renderedViewport = key
        pendingViewport = restore
        menuScroll = null; tabsScroll = null
        playerLabel = null; playerTime = null; playerProgress = null; playButton = null; playerError = null; restartButton = null; samplePlayButton = null
        etaLabel = null
        if (screen == Screen.PICK && !state.safety.locked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = if (wide) row() else column()
        root.setBackgroundColor(bg)
        val nav = column().apply { setPadding(dp(16), dp(14), dp(16), dp(10)) }
        nav.copy("▥  여운FM", 25f, accent, true)
        if (wide) {
            nav.copy("OUR WAY HOME", 12f, muted); nav.space(22)
            Screen.entries.forEach { item -> nav.button(item.label, item == screen) { go(item) } }
            nav.space(20); nav.copy("같은 여행,\n서로 다른 기억.", 17f, muted)
            menuScroll = ScrollView(this).apply { id = R.id.yeounfm_menu_scroll; isVerticalScrollBarEnabled = false; addView(nav) }
            root.addView(menuScroll, LinearLayout.LayoutParams(dp(200), -1))
        } else {
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
            val tabs = row()
            Screen.entries.forEach { item ->
                tabs.addView(Button(this).apply {
                    text = item.label; isAllCaps = false; setTextColor(if (screen == item) bg else muted)
                    background = shape(if (screen == item) accent else cardBg, 10); minHeight = dp(52)
                    setOnClickListener { go(item) }
                }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginEnd = dp(6) })
            }
            tabsScroll = HorizontalScrollView(this).apply { id = R.id.yeounfm_tabs_scroll; isHorizontalScrollBarEnabled = false; addView(tabs) }
            root.addView(tabsScroll, LinearLayout.LayoutParams(-1, dp(60)))
        }
        val main = column().apply { setPadding(dp(if (wide) 28 else 20), dp(14), dp(if (wide) 28 else 20), dp(24)) }
        main.copy(if (state.settings.demo) "● DEMO MODE  /  가상 여정 데이터" else "● 실제 차량 모드  /  ${BuildConfig.FLAVOR}", 14f, if (state.settings.demo) accent else amber, true)
        main.copy(state.safety.label, 14f, muted)
        main.space(16)
        if (state.error.isNotBlank() && !state.safety.locked) main.copy(state.error, 18f, amber)
        when {
            state.loading -> main.copy("기기의 기록을 불러오는 중…", 24f)
            state.safety.locked && screen != Screen.PLAYER -> main.locked()
            !state.settings.consent && screen != Screen.SETTINGS -> main.welcome()
            else -> when (screen) {
                Screen.HOME -> main.home()
                Screen.JOURNEY -> main.journey()
                Screen.PICK -> main.memoryPick()
                Screen.PREPARE -> main.prepare()
                Screen.PLAYER -> main.player()
                Screen.LIBRARY -> main.library()
                Screen.SETTINGS -> main.settings()
            }
        }
        val scroll = ScrollView(this).apply { id = R.id.yeounfm_content_scroll; isFillViewport = true; addView(main); isVerticalScrollBarEnabled = false }
        contentScroll = scroll
        root.addView(scroll, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val menu = menuScroll
        val tabs = tabsScroll
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (contentScroll !== scroll) return
                scroll.scrollTo(0, restore.contentY)
                menu?.scrollTo(0, restore.menuY)
                tabs?.scrollTo(restore.tabsX, 0)
                pendingViewport = null
            }
        })
        setContentView(root); updatePlayer()
    }
    private fun LinearLayout.welcome() {
        heading("여운FM에 오신 것을 환영해요", "귀갓길에,\n우리의 기억을 틀어요.", "같은 하루를 보낸 사람들의 서로 다른 마음이\n우리만의 라디오가 됩니다.")
        card { copy("01  오늘의 장소를 확인하고", 22f, white, true); copy("02  각자의 기억을 비밀로 고르면", 22f, white, true); copy("03  방송에서 함께 공개해요", 22f, accent, true) }
        button("개인정보 안내 확인하고 시작", true) { go(Screen.SETTINGS) }
    }
    private fun LinearLayout.locked() {
        heading("RIDE SAFE", "지금은 귀 기울일 시간", "기억 입력과 기록 관리는 정차 후에 이용해 주세요.\n차량에서는 시스템 미디어 컨트롤로 방송을 조작할 수 있습니다.")
        button("라디오 플레이어", true) { go(Screen.PLAYER) }
        if (!app.safety.automotive) button("DEMO · 정차 상태로 전환") { model.simulateDriving(false) }
    }
    private fun LinearLayout.home() {
        heading("YOUR RETURN TRIP, ON AIR", "오늘, 어떤 순간이 남았나요?", "같은 여행, 서로 다른 기억. 우리만의 귀갓길 라디오.")
        card {
            copy(if (state.trip == null) "아직 시작하지 않은 오늘의 이야기" else if (state.trip?.status == TripStatus.RECORDING) "오늘의 여행을 기록하고 있어요" else "여행은 끝나도 이야기는 이어져요", 23f, white, true)
            copy(state.providerStatus, 16f, muted)
            copy("${state.visits.count { it.usable }}곳 확인  ·  ${state.completedNames.size}명 참여", 18f, accent)
            state.visits.filter { it.usable }.take(3).takeIf { it.isNotEmpty() }?.let { copy(it.joinToString(" → ") { visit -> visit.placeName }, 18f, muted) }
            if (state.trip == null) button(if (state.settings.demo) "춘천 DEMO 여정 불러오기" else "여행 기록 시작", true) { model.startTrip() }
            else { button("오늘의 방문 장소 확인", true) { go(Screen.JOURNEY) }; button("각자의 기억 고르기") { go(Screen.PICK) } }
        }
        card { copy("기억이 모이면, 방송이 시작됩니다", 22f, white, true); copy("선택은 서로에게 비공개. 방송에서 처음 만나는 마음을 발견해 보세요.", 18f, muted); button("라디오 만들기", enabled = state.trip != null) { go(Screen.PREPARE) } }
        button("지난 방송 듣기 · ${state.saved.size}편") { go(Screen.LIBRARY) }
    }
    private fun LinearLayout.journey() {
        heading("01 / JOURNEY SYNC", "함께 지나온 곳", "실제 방문한 곳만 확인해 주세요. 미확인 장소는 방송에 사용하지 않습니다.")
        if (state.trip == null) { button("여행 기록 시작", true) { model.startTrip() }; return }
        copy(state.providerStatus, 16f, muted)
        state.visits.filter { it.verification != Verification.REJECTED }.forEach { visit ->
            card {
                copy("${visit.sequence + 1}.  ${visit.placeName}", 23f, white, true)
                val label = when (visit.verification) { Verification.CONFIRMED -> "✓ 방문 확인"; Verification.USER_ADDED -> "✓ 직접 추가"; else -> "? 방문 여부 확인 필요" }
                copy("$label  ·  ${visit.source.name}", 16f, if (visit.usable) accent else amber)
                copy(if (visit.source == DataSource.RECENT_DESTINATION) "최근 목적지 참고자료 · 방문 시각 알 수 없음" else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(visit.arrivalTime)), 15f, muted)
                if (!visit.usable) button("실제로 방문했어요", true) { model.verifyVisit(visit.id, Verification.CONFIRMED) }
                button("장소명 수정") { inputDialog("장소 이름", visit.placeName, 80) { model.renameVisit(visit.id, it) } }
                button("방문하지 않았어요 / 제거") { model.verifyVisit(visit.id, Verification.REJECTED) }
            }
        }
        button("누락된 장소 직접 추가") { inputDialog("직접 방문한 장소", "", 80) { model.addVisit(it) } }
        card {
            copy("귀가 도착 확인", 23f, white, true)
            copy("방송의 표시 이름: ${state.trip?.returnDestination}", 17f, muted)
            val bound = state.trip?.returnTarget
            copy(bound?.let { "지정한 목적지: ${it.name}\n${it.address}" } ?: "귀가 목적지가 연결되지 않았습니다. 표시 이름만으로 자동 종료하지 않습니다.", 17f, if (bound != null) accent else amber)
            val current = state.currentDestination?.destination
            if (current != null) copy("현재 내비 목적지: ${current.name}\n${current.address}", 17f, muted)
            button("현재 내비 목적지를 귀가 목적지로 지정", enabled = current != null) {
                confirm("귀가 목적지로 지정할까요?", "${current?.name}\n${current?.address}\n\n자동 종료 확인을 위해 목적지 식별 정보와 주소를 기기에 보관합니다. 방문 기록 종료 후에도 도착 확인을 계속하며, 연결 해제·여정 삭제·동의 철회 시 중단합니다.") { model.bindCurrentReturnTarget() }
            }
            button("방송의 귀가 표시 이름 수정") { inputDialog("방송에 표시할 귀가 이름 · 도착 판정에는 사용하지 않습니다", state.trip?.returnDestination ?: "", 80) { model.returnDestination(it) } }
            if (bound != null) button("귀가 목적지 연결 해제") { model.clearReturnTarget() }
        }
        button("내비 연결 / 재연결") { model.resumeTrip() }
        if (state.trip?.status == TripStatus.RECORDING) {
            button("오늘 여행 기록 종료") { model.endTrip() }
        }
        button("기억 선택으로", true, state.visits.any { it.usable }) { go(Screen.PICK) }
    }
    private fun LinearLayout.memoryPick() {
        heading("02 / MEMORY PICK", if (pickStep == 0) "각자의 마음을, 비밀로." else "$nickname 님의 기억", "장소 하나, 마음 하나면 충분해요. 선택은 방송에서 처음 공개됩니다.")
        val visits = state.visits.filter { it.usable }
        if (visits.isEmpty()) { copy("먼저 방문 장소를 확인해 주세요.", 20f, amber); button("장소 확인하기", true) { go(Screen.JOURNEY) }; return }
        when (pickStep) {
            0 -> {
                card { copy("${state.completedNames.size}개의 기억이 저장되었어요", 24f, accent, true); copy(if (state.completedNames.isEmpty()) "첫 번째 참여자에게 화면을 건네주세요." else "참여 완료: ${state.completedNames.joinToString(", ")}\n장소와 감정은 아직 비공개예요.", 18f, muted) }
                button("내 차례 시작", true, state.completedNames.size < 8) { pickStep = 1; pickStarted = SystemClock.elapsedRealtime(); render() }
                button("참여 건너뛰기 / 방송 준비") { go(Screen.PREPARE) }
                if (state.completedNames.isNotEmpty()) button("모든 참여 기록 다시 고르기") { confirm("참여 기록을 초기화할까요?", "모든 참여자의 비공개 선택을 삭제합니다.") { model.resetPicks() } }
            }
            1 -> {
                copy("STEP 1  ·  누가 참여하나요?", 22f, accent, true)
                listOf("아빠", "아이", "엄마", "나", "친구").filter { it !in state.completedNames }.forEach { name -> button(name) { nickname = name; pickStep = 2; render() } }
                button("닉네임 직접 입력") { inputDialog("닉네임", "", 20) { value -> if (value !in state.completedNames) { nickname = value; pickStep = 2; render() } else model.message("이미 참여한 닉네임입니다.") } }
                button("이번에는 건너뛰기") { clearPick(); render() }
            }
            2 -> {
                copy("STEP 2  ·  가장 기억에 남은 장소는?", 22f, accent, true)
                visits.forEach { visit -> button(visit.placeName) { selectedVisit = visit.id; pickStep = 3; render() } }
                button("닉네임 다시 선택") { pickStep = 1; render() }
            }
            3 -> {
                copy("STEP 3  ·  그곳에서 어떤 마음이었나요?", 22f, accent, true)
                Emotions.choices.forEach { item -> button(item, emotion == item) { emotion = item; render() } }
                button("감정 직접 입력") { inputDialog("내가 느낀 마음", "", 40) { emotion = it; render() } }
                if (emotion.isNotBlank()) {
                    copy("선택: $emotion", 18f, accent)
                    button("이 마음으로 선택 완료", true) { sealPick("") }
                    button("한마디 추가하고 완료 · 선택 사항") { inputDialog("직접 남기는 한마디 · 방송에서 공개됩니다", "", 120) { sealPick(it) } }
                }
                button("장소 다시 선택") { emotion = ""; pickStep = 2; render() }
            }
        }
    }
    private fun sealPick(note: String) {
        model.submitPick(nickname, selectedVisit, emotion, note, SystemClock.elapsedRealtime() - pickStarted)
        clearPick(); render()
    }
    private fun LinearLayout.prepare() {
        heading("03 / REVEAL RADIO", "우리의 주파수를 맞추는 중", "각자의 기억을 차례로 공개하고, 같았던 마음과 달랐던 마음을 연결합니다.")
        card {
            copy("${state.completedNames.size}명이 함께 만드는 방송", 24f, accent, true)
            copy("확인된 장소 ${state.visits.count { it.usable }}곳 · 저장된 기억 ${state.completedNames.size}개", 18f)
            copy("방송 생성: 기기 내 사실 기반 템플릿\n외부 AI 전송 없음 · 네트워크 없이 사용 가능", 16f, muted)
            etaLabel = text("", 17f, muted).also { addView(it) }; updateEta()
        }
        listOf(30 to "30초 · 하이라이트", 60 to "약 1분 · 기본 방송", 180 to "약 3분 · 확장 대화형").forEach { (seconds, label) -> button(label, requestedSeconds == seconds) { requestedSeconds = seconds; render() } }
        copy("선택한 길이는 목표입니다. 실제 음성 길이는 입력량과 음성 엔진에 따라 달라지며 재생 시 확인합니다.", 15f, muted)
        button(if (state.busy) "방송을 만드는 중…" else "저장된 기억으로 방송 만들기", true, !state.busy && state.completedNames.isNotEmpty()) { model.generate(requestedSeconds) }
        if (state.episode != null) button("완성된 방송으로 이동", true) { go(Screen.PLAYER) }
        if (state.completedNames.isEmpty()) button("먼저 기억 고르기") { go(Screen.PICK) }
        if (!app.safety.automotive && state.settings.demo) { button("DEMO · 남은 시간 45초") { model.remaining(45) }; button("DEMO · 남은 시간 40분") { model.remaining(2400) } }
    }
    private fun LinearLayout.player() {
        heading("ON YOUR FREQUENCY", "우리의 귀갓길 라디오", "자동으로 재생되지 않습니다. 재생을 누르면 기억이 공개됩니다.")
        val episode = state.episode
        if (episode != null) {
            card {
                copy(if (episode.source == DataSource.DEMO) "DEMO EPISODE" else "YOUR EPISODE", 14f, accent, true)
                copy(episode.title, 25f, white, true)
                copy("▂  ▅  ▃  █  ▆  ▂  ▄  ▇  ▃  ▅  ▂", 30f, accent)
                playerLabel = text("방송 준비", 17f, muted).also { addView(it) }
                playerProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { progressTintList = android.content.res.ColorStateList.valueOf(accent) }.also { addView(it, LinearLayout.LayoutParams(-1, dp(12))) }
                playerTime = text("00:00 / --:--", 16f, muted).also { addView(it) }
                playButton = button("▶  방송 재생", true, state.settings.voice && state.settings.consent) { playEpisode() }
                restartButton = button("처음부터 다시 듣기", enabled = playback.episodeId == episode.id && playback.audioPath != null) { command(RadioService.RESTART) }
                button("재생 종료") { command(RadioService.STOP) }
                playerError = text("", 17f, amber).also { addView(it) }
                if (!state.safety.locked) {
                    button(if (episode.saved) "✓ 기기에 보관됨 · 음성 갱신" else "이 방송을 내 기기에 보관") { confirm("방송을 보관할까요?", "대본과 합성 음성을 앱 내부에 보관합니다. 직접 삭제할 때까지 유지되며 외부 공유는 하지 않습니다.") { model.saveEpisode() } }
                    if (!scriptRevealed) button("대본 펼치기 · 선택이 공개됩니다") { scriptRevealed = true; render() }
                    else { copy("방송 대본", 21f, white, true); episode.lines.forEach { copy(it.section, 14f, accent, true); copy(it.text, 20f) } }
                }
            }
        } else { card { copy("아직 만들어진 방송이 없어요", 23f, white, true); if (!state.safety.locked) button("기억을 모아 라디오 만들기", true) { go(Screen.PREPARE) } } }
        if (!state.safety.locked) card {
            copy("기기에 한국어 음성이 없나요?", 21f, white, true)
            copy("미리 제작한 한국어 고정 샘플로 재생을 확인할 수 있어요. 현재 선택한 답변은 반영되지 않습니다.", 17f, muted)
            button("고정 시연 샘플 듣기 · 현재 답변 미반영", enabled = state.settings.voice && state.settings.consent) { command(RadioService.SAMPLE, true) }
        }
        if (playback.episodeId == "fixed-sample") { copy("고정 샘플 · 현재 응답 미반영", 20f, amber, true); samplePlayButton = button(if (playback.playing) "샘플 일시정지" else "샘플 이어 듣기") { command(if (playback.playing) RadioService.PAUSE else RadioService.PLAY) }; button("샘플 종료") { command(RadioService.STOP) } }
        if (!app.safety.automotive && state.settings.demo) { button("DEMO · 내비 안내 우선 시뮬레이션") { command(RadioService.NAVIGATION) }; button("DEMO · 귀가 목적지 도착") { model.arrived() }; button(if (state.safety.locked) "DEMO · 정차 전환" else "DEMO · 주행 전환 / 입력 잠금") { model.simulateDriving(!state.safety.locked) } }
    }
    private fun playEpisode() {
        val episode = state.episode ?: return
        if (playback.episodeId == episode.id && (playback.preparing || playback.audioPath != null) && playback.error.isBlank()) {
            command(if (playback.playing || (playback.preparing && playback.playWhenReady)) RadioService.PAUSE else RadioService.PLAY)
        } else {
            val intent = RadioService.commandIntent(this, RadioService.PREPARE).putExtra("episode", Codec.episode(episode).json)
            startForegroundService(intent)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
    }
    private fun updatePlayer() {
        val matching = playback.episodeId == state.episode?.id
        playerLabel?.text = if (matching) "${playback.section} · 코너 위치는 대본 길이 기준 추정" else "재생 버튼을 눌러 공개해 주세요"
        playerProgress?.max = if (matching) playback.duration.coerceAtLeast(1) else 1
        playerProgress?.progress = if (matching) playback.position else 0
        fun time(ms: Int) = "%02d:%02d".format(ms / 60_000, ms / 1000 % 60)
        playerTime?.text = if (matching && playback.duration > 0) "${time(playback.position)} / ${time(playback.duration)}" else "00:00 / --:--"
        playButton?.text = if (matching && playback.preparing) { if (playback.playWhenReady) "Ⅱ  준비 완료 후 재생 취소" else "▶  준비 완료 후 재생" } else if (matching && playback.playing) "Ⅱ  일시정지" else "▶  방송 재생"
        playButton?.isEnabled = state.settings.voice && state.settings.consent
        restartButton?.isEnabled = matching && playback.audioPath != null && !playback.preparing
        samplePlayButton?.text = if (playback.playing) "샘플 일시정지" else "샘플 이어 듣기"
        samplePlayButton?.isEnabled = !playback.preparing
        playerError?.text = if (matching) playback.error else ""
        if (screen == Screen.PLAYER && playback.episodeId == "fixed-sample" && playerLabel != null) playerLabel?.text = "고정 샘플 재생 중 · 현재 답변 미반영"
    }
    private fun updateEta() {
        etaLabel?.text = app.rideTime.remainingSeconds()?.let { "남은 귀갓길 ${it / 60}분 ${it % 60}초" } ?: "남은 이동시간 미확인 · 길이를 직접 선택해 주세요"
    }
    private fun LinearLayout.library() {
        heading("SAVED FREQUENCIES", "다시 듣고 싶은 하루", "직접 보관한 방송만 이곳에 남습니다.")
        if (state.saved.isEmpty()) copy("아직 보관한 방송이 없어요. 플레이어에서 원하는 방송만 보관해 주세요.", 20f, muted)
        state.saved.forEach { episode -> card {
            copy(episode.title, 23f, white, true)
            copy("${SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(episode.createdAt))} · ${episode.source.name}", 16f, muted)
            button("방송 열기 / 다시 듣기", true) { model.openEpisode(episode.id); go(Screen.PLAYER) }
            button("이 방송 삭제") { confirm("방송을 삭제할까요?", "보관한 대본과 음성 파일을 기기에서 삭제합니다.") { model.deleteEpisode(episode.id) } }
        } }
    }
    private fun LinearLayout.settings() {
        heading("YOUR MEMORIES, YOUR CHOICE", "기억을 맡기는 방법", "수집과 보관은 언제나 직접 선택합니다.")
        if (!state.settings.consent) card {
            copy("여행 기록 · 방송 활용 동의", 23f, white, true)
            copy("직접 시작한 여정의 장소·시간, 참여자의 닉네임·장소·감정·한마디를 기기 안에서 처리합니다. 귀가 목적지를 연결하면 그 목적지의 내비 식별 정보와 주소도 기기에 보관해 도착을 확인합니다. 참여 내용은 함께 듣는 방송에서 공개됩니다. 상시 녹음, 외부 AI 전송, 외부 공유는 사용하지 않습니다.", 18f, muted)
            copy("여정과 참여 기록은 다음 여정을 시작하거나 삭제할 때까지 보관합니다. 방송 보관은 기본 꺼짐이며, 직접 보관한 방송은 삭제할 때까지 유지합니다. 동의를 철회하면 모든 기록과 방송을 삭제합니다.", 18f, muted)
            val consent = CheckBox(this@MainActivity).apply { text = "위 기록·방송 활용에 동의합니다"; textSize = 18f; minHeight = dp(60); setTextColor(white) }
            val guardian = CheckBox(this@MainActivity).apply { text = "모든 참여자가 공개에 동의하며, 아동 참여 시 보호자가 동의합니다"; textSize = 18f; minHeight = dp(60); setTextColor(white) }
            addView(consent); addView(guardian)
            button("동의하고 시작", true) { if (consent.isChecked && guardian.isChecked) { model.consent(true); go(Screen.HOME) } else model.message("기록 활용과 참여자·보호자 동의를 모두 확인해 주세요.") }
        } else {
            card {
                copy("기록·방송 활용 동의됨", 23f, accent, true)
                copy("외부 AI 전송 없음 · API 키 불필요\n음성은 오프라인 한국어 엔진만 사용\n자동 방송 보관 없음 · 상시 녹음 없음", 18f, muted)
                button(if (state.settings.voice) "음성 기능 끄기" else "음성 기능 켜기") { model.voice(!state.settings.voice) }
            }
            card {
                copy("데이터 모드", 23f, white, true)
                copy("현재: ${if (state.settings.demo) "DEMO · 가상 데이터" else "실제 차량"}\nAPK: ${BuildConfig.FLAVOR} ${BuildConfig.VERSION_NAME}", 18f, muted)
                PlatformJourneyProvider.requiredPermission?.takeIf { !state.settings.demo }?.let { permission ->
                    val granted = checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) copy("✓ 내비 조회 권한 허용됨", 17f, accent)
                    else button("내비 권한 요청") { requestPermissions(arrayOf(permission), 43) }
                }
                button(if (state.settings.demo) "실제 차량 모드로 전환" else "Demo Mode로 전환") { confirm("모드를 전환할까요?", "현재 여정과 참여 선택을 지웁니다. 직접 보관한 방송은 유지합니다.") { model.mode(!state.settings.demo) } }
                if (!app.safety.automotive) button("DEMO · 주행 제한 확인") { model.simulateDriving(true) }
            }
            card {
                copy("내 기록 관리", 23f, white, true)
                button("현재 여행·참여 기록 삭제") { confirm("여행 기록을 삭제할까요?", "현재 장소와 참여 선택을 삭제합니다. 직접 보관한 방송은 유지합니다.") { model.deleteTrip() } }
                button("새 여정 시작") { confirm("새로운 여정을 시작할까요?", "이전 여정과 참여 선택을 지우고 새로 기록합니다.") { model.startTrip() } }
                button("동의 철회 및 모든 데이터 삭제") { confirm("동의를 철회할까요?", "여행·참여 기록·모든 보관 방송과 음성을 삭제하고 수집을 중단합니다.") { model.revokeAndDelete() } }
            }
        }
        copy("여운FM ${BuildConfig.VERSION_NAME} · Podcast\n차량 SDK와 실제 차량 연동은 Pleos 환경에서 별도 검증이 필요합니다. 고정 샘플 음성은 생성 방송과 구분해 제공합니다.", 15f, muted)
    }
    private fun command(action: String, foreground: Boolean = false) { val intent = RadioService.commandIntent(this, action); if (foreground) startForegroundService(intent) else startService(intent) }
    private fun inputDialog(title: String, initial: String, limit: Int, onValue: (String) -> Unit) {
        if (state.safety.locked) return
        val input = EditText(this).apply { setText(initial); textSize = 20f; filters = arrayOf(InputFilter.LengthFilter(limit)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        activeDialog = AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("취소", null).setPositiveButton("확인") { _, _ ->
            if (!app.safety.state.value.locked) input.text.toString().trim().takeIf { it.isNotBlank() }?.let(onValue)
        }.create().also { it.show() }
    }
    private fun confirm(title: String, body: String, action: () -> Unit) {
        if (state.safety.locked) return
        activeDialog = AlertDialog.Builder(this).setTitle(title).setMessage(body).setNegativeButton("취소", null).setPositiveButton("확인") { _, _ -> if (!app.safety.state.value.locked) action() }.create().also { it.show() }
    }
}
