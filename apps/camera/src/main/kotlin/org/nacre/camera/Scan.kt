package org.nacre.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import android.widget.Toast
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class Scanner(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to BarcodeFormat.entries,
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    override fun analyze(image: ImageProxy) = image.use {
        val plane = it.planes[0]
        val width = plane.rowStride
        val height = it.height
        val bytes = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
        val text = decode(bytes, width, height, it.width) ?: decode(
            rotate(bytes, width, height),
            height,
            width,
            height
        )
        if (text != null) onResult(text)
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int, visible: Int): String? {
        if (bytes.size < width * height) return null
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, visible, height, false)
        return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
            .getOrNull()
            .also { reader.reset() }
    }

    private fun rotate(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) rotated[x * height + height - 1 - y] = bytes[y * width + x]
        }
        return rotated
    }
}

private data class Action(val label: String, val run: (Context) -> Unit)

private fun wifiField(text: String, key: String): String? {
    val match = Regex("""(?:^|;)$key:((?:\\.|[^;\\])*)""").find(text.removePrefix("WIFI:"))
    return match?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
}

private fun joinWifi(context: Context, text: String) {
    val ssid = wifiField(text, "S") ?: return
    val password = wifiField(text, "P").orEmpty()
    val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
    when (wifiField(text, "T")?.uppercase()) {
        "SAE" -> builder.setWpa3Passphrase(password)
        "WPA", "WPA2" -> builder.setWpa2Passphrase(password)
        else -> if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
    }
    val suggestion = runCatching { builder.build() }.getOrNull() ?: return
    val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
        .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    open(context, intent)
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show() }
}

private fun kindOf(text: String): Pair<String, Action?> {
    val lower = text.lowercase()
    fun view(label: String, action: String = Intent.ACTION_VIEW) =
        Action(label) { open(it, Intent(action, Uri.parse(text))) }

    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> "Link" to view("Open link")
        lower.startsWith("wifi:") -> "Wi-Fi network" to Action("Connect") { joinWifi(it, text) }
        lower.startsWith("tel:") -> "Phone number" to view("Call", Intent.ACTION_DIAL)
        lower.startsWith("mailto:") -> "Email" to view("Write email", Intent.ACTION_SENDTO)
        lower.startsWith("smsto:") || lower.startsWith("sms:") ->
            "Message" to view("Write message", Intent.ACTION_SENDTO)
        lower.startsWith("geo:") -> "Location" to view("Show on map")
        else -> "Text" to null
    }
}

@Composable
fun ScanResult(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (kind, action) = kindOf(text)
    val label = if (kind == "Wi-Fi network") wifiField(text, "S") ?: text else text

    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(kind, style = MaterialTheme.typography.labelLarge)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, false)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                action?.let { Button(onClick = { it.run(context) }) { Text(it.label) } }
                OutlinedButton(onClick = { copy(context, text) }) { Text("Copy") }
                OutlinedButton(onClick = { share(context, text) }) { Text("Share") }
                OutlinedButton(onClick = onDismiss) { Text("Scan again") }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Scanned code", text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    open(context, Intent.createChooser(intent, null))
}
