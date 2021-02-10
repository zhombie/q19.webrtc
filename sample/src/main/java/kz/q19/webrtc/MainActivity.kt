package kz.q19.webrtc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import kz.q19.domain.model.webrtc.IceServer
import kz.q19.webrtc.core.ui.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    private var surfaceViewRenderer: SurfaceViewRenderer? = null

    private var peerConnectionClient: PeerConnectionClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceViewRenderer = findViewById(R.id.surfaceViewRenderer)

        requestPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 123) {
            if (permissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                peerConnectionClient = PeerConnectionClient(this)
                peerConnectionClient?.setLocalSurfaceView(surfaceViewRenderer)
                peerConnectionClient?.createPeerConnection(
                    Options(
                        isLocalAudioEnabled = true,
                        isLocalVideoEnabled = true,
                        videoCodecHwAcceleration = true,
                        iceServers = listOf(
                            IceServer(
                                url = "stun:global.stun.twilio.com:3478?transport=udp",
                                urls = "stun:global.stun.twilio.com:3478?transport=udp"
                            )
                        )
                    )
                )
                peerConnectionClient?.initLocalCameraStream(
                    isMirrored = true,
                    isZOrderMediaOverlay = false
                )
                peerConnectionClient?.addLocalStreamToPeer()
            } else {
                requestPermissions()
            }
        }
    }

    override fun onDestroy() {
        peerConnectionClient?.dispose()
        peerConnectionClient = null
        super.onDestroy()
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            123
        )
    }

}