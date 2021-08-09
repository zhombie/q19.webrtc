package kz.q19.webrtc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kz.q19.domain.model.webrtc.IceServer
import kz.q19.webrtc.core.ui.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private val iceServers by lazy {
            listOf(
                IceServer(
                    url = "stun:global.stun.twilio.com:3478?transport=udp",
                    urls = "stun:global.stun.twilio.com:3478?transport=udp"
                )
            )
        }

        private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private var fullSurfaceViewRenderer: SurfaceViewRenderer? = null
    private var miniSurfaceViewRenderer: SurfaceViewRenderer? = null

    private var peerConnectionClient: PeerConnectionClient? = null

    private val openLocationSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "ActivityResultContracts.StartActivityForResult() -> Success")
            } else {
                Log.d(TAG, "ActivityResultContracts.StartActivityForResult() -> Fail")
            }
        }

    private val requestPermissions = requestMultiplePermissions(
        onAllGranted = {
            peerConnectionClient = PeerConnectionClient(this)
            peerConnectionClient?.setLocalSurfaceView(fullSurfaceViewRenderer)
            peerConnectionClient?.createPeerConnection(
                Options(
                    isLocalAudioEnabled = true,
                    isLocalVideoEnabled = true,
                    videoCodecHwAcceleration = true,
                    iceServers = iceServers
                )
            )
            peerConnectionClient?.initLocalCameraStream(
                isMirrored = true,
                isZOrderMediaOverlay = false
            )
            peerConnectionClient?.addLocalStreamToPeer()
        },
        onDenied = {
            launchApplicationSettings()
        },
        onExplained = {
            AlertDialog.Builder(this)
                .setTitle("Attention")
                .setMessage("Grant access to permissions: ${permissions.contentToString()}")
                .setPositiveButton("Grant access") { dialog, _ ->
                    dialog.dismiss()
                    launchApplicationSettings()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .show()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fullSurfaceViewRenderer = findViewById(R.id.fullSurfaceViewRenderer)
        miniSurfaceViewRenderer = findViewById(R.id.miniSurfaceViewRenderer)

        var isSet = false
        findViewById<Button>(R.id.button).setOnClickListener {
            isSet = if (isSet) {
                fullSurfaceViewRenderer?.let {
                    peerConnectionClient?.setLocalVideoSink(
                        surfaceViewRenderer = it,
                        isMirrored = true,
                        isZOrderMediaOverlay = false
                    )
                }
                false
            } else {
                miniSurfaceViewRenderer?.let {
                    peerConnectionClient?.setLocalVideoSink(
                        surfaceViewRenderer = it,
                        isMirrored = true,
                        isZOrderMediaOverlay = false
                    )
                }
                true
            }
        }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()

        peerConnectionClient?.startLocalVideoCapture()
    }

    override fun onPause() {
        super.onPause()

        peerConnectionClient?.stopLocalVideoCapture()
    }

    override fun onDestroy() {
        peerConnectionClient?.dispose()
        peerConnectionClient = null

        super.onDestroy()

        fullSurfaceViewRenderer = null
        miniSurfaceViewRenderer = null
    }

    private fun requestPermissions() {
        requestPermissions.launch(permissions)
    }

    private fun launchApplicationSettings() {
        openLocationSettings.launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }

}