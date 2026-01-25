package com.xxxgreen.mvx.downloader4vsco

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.gms.ads.*
import com.google.android.material.textfield.TextInputEditText
import com.xxxgreen.mvx.downloader4vsco.VscoLoader.extractDownloadUrls
import com.xxxgreen.mvx.downloader4vsco.VscoLoader.extractThumbnail
import kotlinx.coroutines.*
import org.jsoup.Jsoup

class MainActivity : AppCompatActivity() {

    private lateinit var etInput: TextInputEditText
    private lateinit var ivPreview: ImageView
    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var downloaderCard: View
    private lateinit var tvTitle: TextView
    private lateinit var adContainer: FrameLayout

    // Receivers
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_LONG).show()
            resetUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init Views
        etInput = findViewById(R.id.etMainInput)
        ivPreview = findViewById(R.id.ivPreview)
        webView = findViewById(R.id.webView)
        loadingLayout = findViewById(R.id.layoutLoading)
        downloaderCard = findViewById(R.id.downloaderCard)
        tvTitle = findViewById(R.id.tvTitle)
        adContainer = findViewById(R.id.adContainer)
        val btnPaste = findViewById<Button>(R.id.btnPaste)
        val btnDownload = findViewById<View>(R.id.btnDownload)

        // Init Utils
        VscoLoader.prepareFileDirs()
        checkPermissions()
        initAdMob()

        // Event Listeners
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                etInput.setText(text)
                handleInput(text)
            }
        }

        btnDownload.setOnClickListener {
            startDownloadService()
        }

        // WebView Setup (for Profile scraping)
        webView.settings.javaScriptEnabled = true
        webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36..."
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()
                if (url.contains("medias/profile")) {
                    Log.d("MainActivity", "Intercepted profile API: $url")
                    // Handle recursive profile scraping here via OkHttp
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        // Register Receivers
        val filter = IntentFilter("DOWNLOAD_FINISHED_ACTION")

        // ContextCompat handles the API level check internally
        ContextCompat.registerReceiver(
            this,
            finishReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Handle Intent (Share)
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                VscoLoader.isShared = true
                etInput.setText(text)
                handleInput(text)
            }
        }
    }

    private fun handleInput(input: String) {
        val url = extractUrl(input) ?: return

        showLoading(true)

        // Launch a coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Fetch the main HTML
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0...").get()
                val html = doc.html()

                // 2. Extract Title
                val title = doc.title().substringBefore("|").trim()
                VscoLoader.mTitle = title

                // 3. Extract Thumbnail (Synchronous)
                val thumbUrl = VscoLoader.extractThumbnail(url, html)

                // 4. Extract Media URLs (Suspend/Async)
                // This will internally do the network call for videos if needed
                val mediaUrls = VscoLoader.extractDownloadUrls(url, html)
                VscoLoader.mMediaUrls.addAll(mediaUrls)

                // 5. Update UI on Main Thread
                withContext(Dispatchers.Main) {
                    if (VscoLoader.mMediaUrls.isNotEmpty()) {
                        tvTitle.text = title
                        // Use Glide to load the extracted thumbnail
                        Glide.with(this@MainActivity).load(thumbUrl).into(ivPreview)
                        showPreviewUI()
                    } else {
                        Toast.makeText(this@MainActivity, "No media found", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showLoading(false)
                }
            }
        }
    }

    private fun startDownloadService() {
        val intent = Intent(this, DownloadService::class.java)
        intent.action = "START_SERVICE"
        startService(intent)

        downloaderCard.alpha = 0.5f // Visual feedback
    }

    // UI Helpers
    private fun showLoading(isLoading: Boolean) {
        loadingLayout.alpha = if (isLoading) 1.0f else 0.0f
        ivPreview.alpha = if (isLoading) 0.5f else 0.0f
    }

    private fun showPreviewUI() {
        loadingLayout.alpha = 0.0f
        ivPreview.alpha = 1.0f
        downloaderCard.alpha = 1.0f
        downloaderCard.visibility = View.VISIBLE
    }

    private fun resetUI() {
        etInput.text = null
        downloaderCard.alpha = 0.0f
        ivPreview.alpha = 0.0f
        VscoLoader.resetVars()
    }

    private fun extractUrl(input: String): String? {
        // Regex logic from C#
        if (input.contains("vsco.co")) {
            // cleanup and return valid URL
            return "https://" + input.substringAfter("http").substringAfter("://")
        }
        return null
    }

    // AdMob
    private fun initAdMob() {
        MobileAds.initialize(this) {}
        val adView = AdView(this)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
        adContainer.addView(adView)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(finishReceiver)
    }
}