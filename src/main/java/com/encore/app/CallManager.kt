package com.encore.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class CallPhase { Idle, Calling, Ringing, Connecting, Active }

object CallManager {
    var phase by mutableStateOf(CallPhase.Idle)
        private set
    var peerId by mutableStateOf("")
        private set
    var peerName by mutableStateOf("")
        private set
    var muted by mutableStateOf(false)
        private set
    var speaker by mutableStateOf(false)
        private set
    var seconds by mutableStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var callId: String? = null
    private var ice: JSONArray = JSONArray()
    private var tracking = false

    /** call the person pressed Answer on (notification button) before the call screen was up; accepted once it shows */
    var acceptCallId by mutableStateOf<String?>(null)
        private set
    val acceptRequested: Boolean get() = acceptCallId != null && acceptCallId == callId

    /** id of the call the app is currently handling (null when idle) */
    val activeCallId: String? get() = callId

    @Volatile private var gen = 0
    @Volatile private var pulled = false

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        CallNotification.ensureChannel(ctx.applicationContext)
        trackForeground()
    }

    /** True while one of the app's screens is on the display, so the in-app call screen can actually be seen. */
    private fun inForeground(): Boolean {
        val st = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(st)
        return st.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** When the app comes to the front while a call is ringing, the in-app screen takes over from the notification. */
    private fun trackForeground() {
        if (tracking) return
        val app = appCtx as? Application ?: return
        tracking = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (phase == CallPhase.Ringing) takeOverRinging()
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun takeOverRinging() {
        appCtx?.let { CallNotification.cancel(it) }
        startRing()
    }

    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    private fun toast(t: String?) {
        val c = appCtx ?: return
        if (!t.isNullOrBlank()) Toast.makeText(c, t, Toast.LENGTH_SHORT).show()
    }

    private fun sendEnd(id: String, action: String) {
        Thread { runCatching { Api.call("POST", "/v1/calls/$id/$action") } }.start()
    }

    /* ---------- outgoing ---------- */

    fun start(cid: String, peer: String, name: String) {
        if (phase != CallPhase.Idle) {
            toast("You are already in a call")
            return
        }
        val g = ++gen
        callId = null
        pulled = false
        peerId = peer
        peerName = name
        muted = false
        speaker = false
        seconds = 0
        phase = CallPhase.Calling
        startRingback()
        Thread {
            try {
                val r = Api.call("POST", "/v1/calls", JSONObject().put("conversation_id", cid))
                val id = r.getJSONObject("call").getString("id")
                val servers = r.optJSONArray("ice_servers") ?: JSONArray()
                ui {
                    if (g == gen) {
                        callId = id
                        ice = servers
                        main.postDelayed({
                            if (g == gen && phase == CallPhase.Calling) {
                                toast("No answer")
                                hangup()
                            }
                        }, 45000L)
                    } else {
                        sendEnd(id, "end")
                    }
                }
            } catch (e: Exception) {
                ui { if (g == gen) finish(e.message ?: "Call failed") }
            }
        }.start()
    }

    /* ---------- incoming ---------- */

    private fun incoming(id: String, from: JSONObject?) {
        val g = ++gen
        callId = id
        pulled = false
        peerId = from?.optString("id") ?: ""
        peerName = from?.optString("display_name") ?: "Unknown"
        muted = false
        speaker = false
        seconds = 0
        phase = CallPhase.Ringing
        if (inForeground()) {
            appCtx?.let { CallNotification.cancel(it) } // the in-app screen takes over from the notification
            startRing()
        } else {
            // the app is in the background, so the in-app screen cannot be seen: the system call notification
            // (heads-up banner / full-screen call) rings instead, and the in-app screen takes over when the app opens
            appCtx?.let { CallNotification.ensureShown(it, id, peerId, peerName) }
        }
        main.postDelayed({
            if (g == gen && phase == CallPhase.Ringing) finish("Missed call")
        }, 60000L)
    }

    /**
     * The app was opened from the "incoming call" notification (app was closed or in the background):
     * the realtime invite was missed, so check the call is still ringing and then show the call screen.
     */
    fun incomingFromPush(id: String, peer: String, name: String, autoAccept: Boolean = false) {
        if (autoAccept) acceptCallId = id // Answer was pressed on the call notification: accept once the call screen is up
        if (phase != CallPhase.Idle || callId == id) return
        Thread {
            try {
                val st = Api.call("GET", "/v1/calls/$id").getJSONObject("call").getString("state")
                if (st == "ringing") {
                    ui {
                        if (phase == CallPhase.Idle) {
                            incoming(id, JSONObject().put("id", peer).put("display_name", name))
                        }
                    }
                } else {
                    ui { if (acceptCallId == id) acceptCallId = null } // already answered or ended: nothing to accept
                }
            } catch (e: Exception) {
                ui { if (acceptCallId == id) acceptCallId = null }
            }
        }.start()
    }

    /** The accept request was handled (or is no longer needed). */
    fun clearAcceptRequest() {
        acceptCallId = null
    }

    fun accept() {
        val id = callId ?: return
        if (phase != CallPhase.Ringing) return
        val g = gen
        phase = CallPhase.Connecting
        stopRing()
        Thread {
            try {
                val r = Api.call("POST", "/v1/calls/$id/accept")
                val servers = r.optJSONArray("ice_servers") ?: JSONArray()
                ui { if (g == gen) startPing(g, id) }
                connectMedia(id, servers, g)
            } catch (e: Exception) {
                ui { if (g == gen) finish(e.message ?: "Call failed") }
            }
        }.start()
    }

    /** Decline while ringing, or hang up in any other state. */
    fun hangup() {
        val id = callId
        if (id != null) sendEnd(id, if (phase == CallPhase.Ringing) "reject" else "end")
        finish(null)
    }

    fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
    }

    fun toggleSpeaker() {
        speaker = !speaker
        (appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isSpeakerphoneOn = speaker
    }

    /* ---------- realtime events (main thread) ---------- */

    fun onEvent(j: JSONObject) {
        if (j.optString("type") != "call") return
        val id = j.optString("call_id")
        when (j.optString("ev")) {
            "invite" -> if (phase == CallPhase.Idle) incoming(id, j.optJSONObject("from"))
            "accepted" -> if (id == callId) {
                if (phase == CallPhase.Calling) {
                    val g = gen
                    stopRingback()
                    phase = CallPhase.Connecting
                    startPing(g, id)
                    connectMedia(id, ice, g)
                } else if (phase == CallPhase.Ringing && j.optString("by") == Session.user?.id) {
                    finish(null) // answered on another device
                }
            }
            "ended" -> if (id == callId) {
                val outgoing = j.optString("by") != peerId
                finish(
                    when (j.optString("state")) {
                        "rejected" -> if (outgoing) "Call declined" else null
                        "missed" -> if (phase == CallPhase.Ringing) "Missed call" else "No answer"
                        else -> "Call ended"
                    }
                )
            }
        }
    }

    /* ---------- finish ---------- */

    private fun finish(note: String?) {
        gen++
        main.removeCallbacksAndMessages(null)
        stopRing()
        stopRingback()
        appCtx?.let { CallNotification.cancel(it) }
        releaseMedia()
        phase = CallPhase.Idle
        callId = null
        acceptCallId = null
        pulled = false
        seconds = 0
        toast(note)
    }

    private fun releaseMedia() {
        try { pc?.close() } catch (e: Exception) {}
        try { pc?.dispose() } catch (e: Exception) {}
        pc = null
        try { audioTrack?.dispose() } catch (e: Exception) {}
        try { audioSource?.dispose() } catch (e: Exception) {}
        audioTrack = null
        audioSource = null
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    /** Incoming call: the phone's own ringtone (at ring volume) plus vibration, following the ringer switch. */
    private fun startRing() {
        if (ringtone != null || vibrator != null) return // already ringing
        val c = appCtx ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        startVibration(c)
        if (mode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(c, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
            if (Build.VERSION.SDK_INT < 28) keepRinging()
        } catch (e: Exception) {
        }
    }

    // Android 8 and 9 have no looping ringtone, so start it again when it ends
    private fun keepRinging() {
        main.postDelayed({
            val r = ringtone ?: return@postDelayed
            try {
                if (!r.isPlaying) r.play()
            } catch (e: Exception) {
            }
            keepRinging()
        }, 2000L)
    }

    private fun startVibration(c: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 900), 0))
            vibrator = v
        } catch (e: Exception) {
        }
    }

    private fun stopRing() {
        try { ringtone?.stop() } catch (e: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (e: Exception) {}
        vibrator = null
    }

    /** Outgoing call: the usual "tu-u tu-u" ringing tone until the other person answers or the call ends. */
    private fun startRingback() {
        stopRingback()
        try {
            val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            t.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            ringback = t
        } catch (e: Exception) {
        }
    }

    private fun stopRingback() {
        try { ringback?.stopTone() } catch (e: Exception) {}
        try { ringback?.release() } catch (e: Exception) {}
        ringback = null
    }

    private fun startTimer(g: Int) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (g != gen || phase != CallPhase.Active) return
                seconds++
                main.postDelayed(this, 1000L)
            }
        }, 1000L)
    }

    private fun startPing(g: Int, id: String) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (g != gen) return
                Thread {
                    try {
                        val st = Api.call("POST", "/v1/calls/$id/ping").getJSONObject("call").getString("state")
                        if (st != "active" && st != "ringing") ui { if (g == gen) finish("Call ended") }
                    } catch (e: Exception) {
                    }
                }.start()
                main.postDelayed(this, 15000L)
            }
        }, 15000L)
    }

    /* ---------- WebRTC + Cloudflare SFU ---------- */

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions()
        )
        val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun parseIce(a: JSONArray): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val raw = o.opt("urls")
            val urls = ArrayList<String>()
            if (raw is JSONArray) {
                for (k in 0 until raw.length()) urls.add(raw.getString(k))
            } else if (raw is String) {
                urls.add(raw)
            }
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("username")) b.setUsername(o.getString("username"))
            if (o.has("credential")) b.setPassword(o.getString("credential"))
            out.add(b.createIceServer())
        }
        return out
    }

    private fun createSdp(pc: PeerConnection, offer: Boolean): SessionDescription {
        val latch = CountDownLatch(1)
        var res: SessionDescription? = null
        var err: String? = null
        val o = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) { res = d; latch.countDown() }
            override fun onCreateFailure(e: String?) { err = e; latch.countDown() }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (offer) pc.createOffer(o, MediaConstraints()) else pc.createAnswer(o, MediaConstraints())
        latch.await(10, TimeUnit.SECONDS)
        return res ?: throw ApiException("webrtc", err ?: "Could not prepare the call")
    }

    private fun setSdp(pc: PeerConnection, d: SessionDescription, local: Boolean) {
        val latch = CountDownLatch(1)
        var err: String? = null
        val o = object : SdpObserver {
            override fun onCreateSuccess(x: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { latch.countDown() }
            override fun onSetFailure(e: String?) { err = e; latch.countDown() }
        }
        if (local) pc.setLocalDescription(o, d) else pc.setRemoteDescription(o, d)
        if (!latch.await(10, TimeUnit.SECONDS)) throw ApiException("webrtc", "Call setup timed out")
        err?.let { throw ApiException("webrtc", it) }
    }

    private fun connectMedia(id: String, iceJson: JSONArray, g: Int) {
        Thread {
            try {
                val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.mode = AudioManager.MODE_IN_COMMUNICATION
                am?.isSpeakerphoneOn = false

                val f = factory()
                val cfg = PeerConnection.RTCConfiguration(parseIce(iceJson)).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                }
                val observer: PeerConnection.Observer = object : PeerConnection.Observer {
                    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                        when (s) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> ui {
                                if (g == gen && phase == CallPhase.Connecting) {
                                    phase = CallPhase.Active
                                    startTimer(g)
                                }
                            }
                            PeerConnection.IceConnectionState.FAILED -> ui {
                                if (g == gen) {
                                    callId?.let { sendEnd(it, "end") }
                                    finish("Connection lost")
                                }
                            }
                            else -> {}
                        }
                    }
                    override fun onIceConnectionReceivingChange(b: Boolean) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(c: IceCandidate?) {}
                    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                    override fun onAddStream(s: MediaStream?) {}
                    override fun onRemoveStream(s: MediaStream?) {}
                    override fun onDataChannel(d: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
                }
                val conn = f.createPeerConnection(cfg, observer)
                    ?: throw ApiException("webrtc", "Could not start the call")
                if (g != gen) { conn.close(); return@Thread }
                pc = conn

                val src = f.createAudioSource(MediaConstraints())
                val track = f.createAudioTrack("mic", src)
                track.setEnabled(!muted)
                audioSource = src
                audioTrack = track
                val tr = conn.addTransceiver(
                    track,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )

                Api.call("POST", "/v1/calls/$id/sfu/session")
                val offer = createSdp(conn, true)
                setSdp(conn, offer, true)
                val mid = tr.mid ?: throw ApiException("webrtc", "Could not prepare the call")
                val body = JSONObject()
                    .put("sessionDescription", JSONObject().put("type", "offer").put("sdp", offer.description))
                    .put("tracks", JSONArray().put(JSONObject().put("location", "local").put("mid", mid).put("trackName", "mic")))
                val res = Api.call("POST", "/v1/calls/$id/sfu/tracks", body)
                if (g != gen) return@Thread
                val answer = res.getJSONObject("sessionDescription")
                setSdp(conn, SessionDescription(SessionDescription.Type.ANSWER, answer.getString("sdp")), false)

                // pull the other person's microphone; it may not be published yet, so retry for a while
                for (i in 0 until 30) {
                    if (g != gen) return@Thread
                    if (pullOnce(id, conn)) break
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                ui {
                    if (g == gen) {
                        callId?.let { sendEnd(it, "end") }
                        finish(e.message ?: "Call failed")
                    }
                }
            }
        }.start()
    }

    private fun pullOnce(id: String, conn: PeerConnection): Boolean {
        if (pulled) return true
        val c = Api.call("GET", "/v1/calls/$id").getJSONObject("call")
        if (c.getString("state") != "active") return false
        if (c.isNull("peer_session")) return false
        val ps = c.getString("peer_session")
        val body = JSONObject().put(
            "tracks",
            JSONArray().put(JSONObject().put("location", "remote").put("sessionId", ps).put("trackName", "mic"))
        )
        val res = Api.call("POST", "/v1/calls/$id/sfu/tracks", body)
        val t0 = res.optJSONArray("tracks")?.optJSONObject(0)
        if (t0 != null && t0.has("errorCode")) return false
        if (res.optBoolean("requiresImmediateRenegotiation")) {
            val sd = res.getJSONObject("sessionDescription")
            setSdp(conn, SessionDescription(SessionDescription.Type.OFFER, sd.getString("sdp")), false)
            val ans = createSdp(conn, false)
            setSdp(conn, ans, true)
            Api.call(
                "PUT", "/v1/calls/$id/sfu/renegotiate",
                JSONObject().put("sessionDescription", JSONObject().put("type", "answer").put("sdp", ans.description))
            )
        }
        pulled = true
        return true
    }
}
