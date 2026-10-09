package codes.t3.android.ui.environments

import codes.t3.android.ui.components.rememberHaptics
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import codes.t3.android.data.pairing.Pairing
import codes.t3.android.data.pairing.PairingTarget

/** Pair with a server: scan the `t3 pair` QR code, or type the address and code. */
@Composable
fun AddEnvironmentScreen(
    initialLink: String?,
    connecting: Boolean,
    error: String?,
    onBack: () -> Unit,
    onConnect: (PairingTarget) -> Unit,
) {
    val prefill = remember(initialLink) { initialLink?.let { Pairing.parse(it) } }
    var address by rememberSaveable { mutableStateOf(prefill?.httpBaseUrl?.substringAfter("://")?.trimEnd('/') ?: "") }
    var code by rememberSaveable { mutableStateOf(prefill?.token ?: "") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else scanError = "Camera access was denied. You can still enter the address and code below."
    }
    val target = Pairing.manual(address, code)
    val haptics = rememberHaptics()
    // Success pops this screen; an error arriving means pairing failed.
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) haptics.reject() }
    val valid = target?.token != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add environment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Surface(
                onClick = {
                    if (scanning) return@Surface
                    scanError = null
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) scanning = true else permission.launch(Manifest.permission.CAMERA)
                },
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().height(280.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (scanning && !inPreview) {
                        QrScanner(
                            onScanned = { raw ->
                                val parsed = Pairing.parse(raw)
                                if (parsed?.token != null) {
                                    haptics.confirm()
                                    scanning = false
                                    address = parsed.httpBaseUrl.substringAfter("://").trimEnd('/')
                                    code = parsed.token
                                    onConnect(parsed)
                                } else {
                                    haptics.reject()
                                    scanError = "Scanned QR code was not recognized as a T3 Code pairing link."
                                }
                            },
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)),
                        )
                        Box(
                            Modifier
                                .size(190.dp)
                                .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(36.dp)),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(112.dp).clip(MaterialShapes.Cookie12Sided.toShape()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Tap to scan", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Scan the code from `t3 pair` or the desktop Connections settings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
            scanError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, start = 8.dp))
            }

            Text(
                "Or enter it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp),
            )
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { input ->
                            // Pasting a full pairing link fills both fields.
                            val parsed = if (input.contains("token=")) Pairing.parse(input) else null
                            if (parsed?.token != null) {
                                address = parsed.httpBaseUrl.substringAfter("://").trimEnd('/')
                                code = parsed.token
                            } else address = input.trim()
                        },
                        label = { Text("Address") },
                        placeholder = { Text("192.168.1.100:3773") },
                        leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(32) },
                        label = { Text("Pairing code") },
                        placeholder = { Text("ABCD2345EFGH") },
                        leadingIcon = { Icon(Icons.Rounded.Key, null) },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { if (valid && !connecting) target?.let(onConnect) }),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(
                error ?: "For machines on your local network or tailnet. Run `t3 pair` on the host to get a code. The machine keeps its own provider credentials.",
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Button(
                onClick = { haptics.contextClick(); target?.let(onConnect) },
                enabled = valid && !connecting,
                contentPadding = ButtonDefaults.MediumContentPadding,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            ) {
                AnimatedContent(connecting, label = "connect") { busy ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting…")
                        } else Text("Connect")
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
