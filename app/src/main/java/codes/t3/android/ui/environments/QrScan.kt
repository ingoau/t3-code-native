package codes.t3.android.ui.environments

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Google Play services' code scanner: a system-provided full-screen scanner that needs no camera permission.
 * Returns a function that starts a scan; results/errors are delivered to the callbacks (cancel = no callback).
 */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    return remember(context) {
        {
            startScan(context, { result(it) }, { error(it) })
        }
    }
}

private fun startScan(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    val scanner = runCatching { GmsBarcodeScanning.getClient(context, options) }.getOrElse {
        onError("QR scanning needs Google Play services. Paste the pairing link or enter the code instead.")
        return
    }
    scanner.startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onResult) }
        .addOnFailureListener { e ->
            // The scanner module may still be downloading on first use: request it and let the user retry.
            runCatching {
                ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            }
            onError("Couldn't open the QR scanner (${e.message ?: "Play services unavailable"}). Try again in a moment, or paste the pairing link.")
        }
}
