package kz.q19.webrtc

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

const val DENIED = "DENIED"
const val EXPLAINED = "EXPLAINED"

inline fun AppCompatActivity.requestMultiplePermissions(
    crossinline onAllGranted: () -> Unit = {},
    crossinline onDenied: (List<String>) -> Unit = {},
    crossinline onExplained: (List<String>) -> Unit = {}
): ActivityResultLauncher<Array<String>> {
    return registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val deniedPermissions = result.filter { !it.value }.map { it.key }
        if (deniedPermissions.isNullOrEmpty()) {
            onAllGranted.invoke()
        } else {
            val map = deniedPermissions.groupBy { permission ->
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    DENIED
                } else {
                    EXPLAINED
                }
            }
            map[DENIED]?.let { onDenied.invoke(it) }
            map[EXPLAINED]?.let { onExplained.invoke(it) }
        }
    }
}
