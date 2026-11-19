package com.printbridge.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.lightColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Receives a PDF (share / open-with / picker) and prints it with the saved settings. */
class PrintActivity : ComponentActivity() {

    private var message by mutableStateOf("")
    private var done by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        message = getString(R.string.reading_pdf)
        setContent {
            MaterialTheme(colors = lightColors(primary = Color(0xFF0B1F3D))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F3F6)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.app_name), fontSize = 20.sp)
                        Text(message, fontSize = 16.sp, modifier = Modifier.padding(vertical = 16.dp))
                        if (done) {
                            Button(
                                onClick = { finish() },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF0B1F3D),
                                    contentColor = Color.White,
                                ),
                            ) { Text(stringResource(R.string.close)) }
                        } else {
                            LinearProgressIndicator(color = Color(0xFF0B1F3D))
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { execute() }
    }

    private suspend fun execute() {
        val s = SettingsStore(this).load()
        try {
            val file = withContext(Dispatchers.IO) { fetchPdf() }
            PrintEngine.printPdf(this, file, s) { m -> message = m }
            message = getString(R.string.sent_to, s.printerName.ifBlank { "" })
            done = true
            delay(1500)
            finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = getString(R.string.error_fmt, e.message ?: e.toString())
            done = true
        }
    }

    private fun incomingUri(): Uri? {
        val i = intent ?: return null
        if (i.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return i.data
    }

    /** Copies the incoming PDF into the cache dir and checks that it really is one. */
    private fun fetchPdf(): File {
        val uri = incomingUri() ?: throw PrintException(getString(R.string.no_pdf))
        val out = File(cacheDir, "job.pdf")
        val scheme = uri.scheme
        val input = if (scheme == "http" || scheme == "https") {
            val c = URL(uri.toString()).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (c.responseCode != 200) throw PrintException(getString(R.string.not_pdf))
            c.inputStream
        } else {
            contentResolver.openInputStream(uri)
                ?: throw PrintException(getString(R.string.no_pdf))
        }
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }

        val head = ByteArray(1024)
        val n = out.inputStream().use { it.read(head) }
        val text = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1) else ""
        if (!text.contains("%PDF-")) throw PrintException(getString(R.string.not_pdf))
        return out
    }
}
