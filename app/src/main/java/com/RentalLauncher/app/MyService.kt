package com.RentalLauncher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "RentalService"

class MyService : Service() {

    // ---- existing location / remote-lock state ----
    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var started = false
    private var deviceId: String = "unknown"

    // ---- screen-share (WebRTC) state ----
    private val main = Handler(Looper.getMainLooper())
    private var liveRef: DatabaseReference? = null
    private var liveCommandListener: ValueEventListener? = null
    private var liveOfferListener: ValueEventListener? = null
    private var liveAdminIceListener: ValueEventListener? = null
    private val appliedAdminIce = mutableSetOf<String>()

    private var eglBase: EglBase? = null
    private var pcFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var pendingOffer: SessionDescription? = null
    private var sharing = false

    companion object {
        // Relays the MediaProjection permission result from ScreenCaptureActivity
        // back to the running service instance (same process).
        private var resultCallback: ((Int, Intent?) -> Unit)? = null

        fun deliverCaptureResult(resultCode: Int, data: Intent?) {
            resultCallback?.invoke(resultCode, data)
            resultCallback = null
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("rental", "RentalLauncher", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val n: Notification = buildNotification("Managed device service active")

        if (!hasLocationPermission()) { // location-type foreground service requires it
            stopSelf()
            return
        }
        try {
            startForegroundTyped(n, projectionActive = false)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }
        signInThenStart()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "rental")
            .setContentTitle("RentalLauncher")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setOngoing(true)
            .build()

    private fun startForegroundTyped(n: Notification, projectionActive: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (projectionActive)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    private fun signInThenStart() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            startListening()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { startListening() }
                .addOnFailureListener { Log.e(TAG, "Firebase sign-in failed", it) }
        }
    }

    private fun startListening() {
        if (started) return
        started = true
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        deviceId = id
        val r = FirebaseDatabase.getInstance().getReference("devices").child(id)
        ref = r

        // Existing nested status (location / online for the LOCATE+LOCK feature).
        r.child("status").child("online").onDisconnect().setValue(false)
        r.child("status").updateChildren(mapOf("online" to true, "lastUpdate" to ServerValue.TIMESTAMP))

        // Top-level fields the admin dashboard actually reads (name/model/online/lastSeen).
        r.child("online").onDisconnect().setValue(false)
        r.updateChildren(
            mapOf(
                "name" to deviceDisplayName(),
                "model" to Build.MODEL,
                "online" to true,
                "lastSeen" to ServerValue.TIMESTAMP
            )
        )

        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                when (s.getValue(String::class.java)) {
                    "LOCATE" -> locate()
                    "LOCK" -> lockDevice()
                }
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "Command listener cancelled: ${e.message}")
            }
        }
        listener = l
        r.child("command").addValueEventListener(l)

        startLiveListening(id)
    }

    private fun deviceDisplayName(): String =
        try {
            Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL
        } catch (e: Exception) {
            Build.MODEL
        }

    private fun locate() {
        if (!hasLocationPermission()) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) pushLocation(loc)
                    else client.lastLocation.addOnSuccessListener { last -> if (last != null) pushLocation(last) }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun pushLocation(l: Location) {
        ref?.child("status")?.updateChildren(
            mapOf(
                "latitude" to l.latitude,
                "longitude" to l.longitude,
                "accuracy" to l.accuracy,
                "lastUpdate" to ServerValue.TIMESTAMP
            )
        )
    }

    private fun lockDevice() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) dpm.lockNow()
    }

    // ---------------- Screen-share (WebRTC) ----------------

    private fun startLiveListening(id: String) {
        val live = FirebaseDatabase.getInstance().getReference("live").child(id)
        liveRef = live

        val commandListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                when (s.getValue(String::class.java)) {
                    "start" -> main.post { beginShareFlow() }
                    "stop" -> main.post { teardownShare() }
                }
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "live/command listener cancelled: ${e.message}")
            }
        }
        liveCommandListener = commandListener
        live.child("command").addValueEventListener(commandListener)

        val offerListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val type = s.child("type").getValue(String::class.java) ?: return
                val sdp = s.child("sdp").getValue(String::class.java) ?: return
                main.post { onRemoteOffer(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)) }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        liveOfferListener = offerListener
        live.child("offer").addValueEventListener(offerListener)

        val adminIceListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                for (child in s.children) {
                    val key = child.key ?: continue
                    if (!appliedAdminIce.add(key)) continue
                    val candidate = child.child("candidate").getValue(String::class.java) ?: continue
                    val sdpMid = child.child("sdpMid").getValue(String::class.java)
                    val sdpMLineIndex = (child.child("sdpMLineIndex").getValue(Int::class.java)) ?: 0
                    main.post { addRemoteIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate)) }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        liveAdminIceListener = adminIceListener
        live.child("adminIce").addValueEventListener(adminIceListener)
    }

    /** Step 1: command == "start" arrived. Ask for screen-capture permission if we don't have it yet. */
    private fun beginShareFlow() {
        if (sharing) return
        sharing = true
        appliedAdminIce.clear()
        if (mediaProjection != null) {
            setupPeerConnectionAndCapture()
            return
        }
        resultCallback = { resultCode, data ->
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                main.post { onCaptureGranted(data) }
            } else {
                main.post { teardownShare() }
            }
        }
        startActivity(
            Intent(this, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun onCaptureGranted(resultData: Intent) {
        startForegroundTyped(buildNotification("Screen sharing to admin"), projectionActive = true)
        setupPeerConnectionAndCapture(resultData)
    }

    /** Step 2: build the WebRTC pipeline and, if an offer already arrived, answer it. */
    private fun setupPeerConnectionAndCapture(resultData: Intent? = null) {
        try {
            if (pcFactory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                        .createInitializationOptions()
                )
                val egl = EglBase.create()
                eglBase = egl
                pcFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
            }
            val factory = pcFactory ?: return

            if (resultData != null && mediaProjection == null) {
                val projManager = getSystemService(MediaProjectionManager::class.java)
                val capturerInstance = ScreenCapturerAndroid(
                    resultData,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            main.post { teardownShare() }
                        }
                    }
                )
                capturer = capturerInstance
                val helper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
                surfaceHelper = helper
                val source = factory.createVideoSource(true)
                videoSource = source
                capturerInstance.initialize(helper, applicationContext, source.capturerObserver)
                val metrics = resources.displayMetrics
                capturerInstance.startCapture(metrics.widthPixels, metrics.heightPixels, 15)
                val track = factory.createVideoTrack("screen0", source)
                videoTrack = track
                mediaProjection = projManager.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
            }

            if (peerConnection == null) {
                val rtcConfig = PeerConnection.RTCConfiguration(
                    listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                )
                rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
            }

            pendingOffer?.let {
                pendingOffer = null
                answerOffer(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up screen capture / peer connection", e)
            teardownShare()
        }
    }

    private fun onRemoteOffer(offer: SessionDescription) {
        if (!sharing) return
        if (peerConnection == null || videoTrack == null) {
            pendingOffer = offer
            return
        }
        answerOffer(offer)
    }

    private fun answerOffer(offer: SessionDescription) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                main.post {
                    videoTrack?.let { pc.addTrack(it, listOf("rental_stream")) }
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    liveRef?.child("answer")?.setValue(
                                        mapOf("type" to desc.type.canonicalForm(), "sdp" to desc.description)
                                    )
                                }
                            }, desc)
                        }
                    }, MediaConstraints())
                }
            }
        }, offer)
    }

    private fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            liveRef?.child("phoneIce")?.push()?.setValue(
                mapOf(
                    "candidate" to candidate.sdp,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex
                )
            )
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                main.post { teardownShare() }
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    /** Step 3: stop sharing (admin disconnected, error, or ICE failed). Keeps the service itself alive. */
    private fun teardownShare() {
        if (!sharing && peerConnection == null && mediaProjection == null) return
        sharing = false
        pendingOffer = null
        appliedAdminIce.clear()

        peerConnection?.close()
        peerConnection = null

        capturer?.let {
            try { it.stopCapture() } catch (e: Exception) { Log.e(TAG, "stopCapture failed", e) }
            it.dispose()
        }
        capturer = null

        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        surfaceHelper?.dispose(); surfaceHelper = null

        mediaProjection?.stop()
        mediaProjection = null

        startForegroundTyped(buildNotification("Managed device service active"), projectionActive = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        listener?.let { ref?.child("command")?.removeEventListener(it) }
        liveCommandListener?.let { liveRef?.child("command")?.removeEventListener(it) }
        liveOfferListener?.let { liveRef?.child("offer")?.removeEventListener(it) }
        liveAdminIceListener?.let { liveRef?.child("adminIce")?.removeEventListener(it) }
        teardownShare()
        pcFactory?.dispose(); pcFactory = null
        eglBase?.release(); eglBase = null
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

private open class SimpleSdpObserver : org.webrtc.SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP create failed: $error") }
    override fun onSetFailure(error: String?) { Log.e(TAG, "SDP set failed: $error") }
}
