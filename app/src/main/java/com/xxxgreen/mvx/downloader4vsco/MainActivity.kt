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
import android.text.Editable
import android.text.TextWatcher
import android.webkit.CookieManager
import java.util.regex.Pattern
import kotlinx.coroutines.*
import org.jsoup.Jsoup

class MainActivity : AppCompatActivity() {
    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:vsco\\.)|(?:vs\\.)?co\\/)", Pattern.CASE_INSENSITIVE)

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

        setupInputListener()
    }

    private fun setupInputListener() {
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Logic from C# OnTextChanged:
                // Only trigger if text was added (length grew) and it wasn't just 1 char (typing)
                // "lengthDiff > 1" usually implies a paste.
                val lengthDiff = count - before

                if (lengthDiff > 1) {
                    val input = s.toString()
                    handleInput(input)
                } else if (s.isNullOrEmpty()) {
                    resetUI()
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun handleInput(rawInput: String) {
        var input = rawInput.trim()

        // 1. Basic Cleaning
        if (input.contains("http://")) {
            input = input.replace("http://", "https://")
        }
        if (input.endsWith("/")) {
            input = input.substring(0, input.length - 1)
        }

        // 2. Regex Validation
        val matcher = VALID_INPUT_REGEX.matcher(input)
        if (!matcher.find()) {
            Log.d("MainActivity", "Invalid Input: $input")
            return
        }

        // 3. Extraction (Ported from HandleInput)
        var url = "https://"
        when {
            input.contains("vs.co") -> url += input.substring(input.indexOf("vs.co"))
            input.contains("vsco.co") -> url += input.substring(input.indexOf("vsco.co"))
            input.contains("https://") -> url = input // Already full url
            else -> {
                // Fallback for simple pastes without protocol
                if (input.contains("vsco.co")) {
                    url += input.substring(input.indexOf("vsco.co"))
                }
            }
        }

        Log.d("MainActivity", "Extracted URL: $url")

        // 4. Gold/Profile Check (Ported Logic)
        // If it is a collection or gallery, we must use WebView, otherwise we scrape directly.
        if (url.contains("/collection")) {
            VscoLoader.isCollection = true
            url += "/1"
            loadInWebView(url)
        } else if (!url.contains("/media") && !url.contains("/video") && !url.contains("vs.co")) {
            // It's likely a profile
            VscoLoader.isProfile = true
            url += "/gallery"
            loadInWebView(url)
        } else {
            // It's a specific media file -> Scrape it
            loadMediaData(url)
        }
    }

    private fun loadInWebView(url: String) {
        Log.d("MainActivity", "Loading in WebView: $url")
        showLoading(true)

        webView.visibility = View.INVISIBLE // Keep invisible, we just want it to process logic
        webView.loadUrl(url)

        // Note: Your MWebViewClient logic handles the scraping from here
    }

    private fun loadMediaData(url: String) {
        Log.d("MainActivity", "Loading Media Data: $url")
        showLoading(true)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Fetch HTML
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()

                val html = doc.html()
                val head = doc.head().html() // VscoLoader.cs used head+body sometimes

                // 2. Extract Title (Ported logic)
                var title = doc.title()
                if (title.contains("|")) title = title.substringBefore("|")
                title = title.trim().replace(" ", "")
                VscoLoader.mTitle = title

                // 3. Extract Thumbnail
                val thumbUrl = VscoLoader.extractThumbnail(url, head + html)

                // 4. Extract Download URLs (Suspend function)
                val downloadUrls = VscoLoader.extractDownloadUrls(url, head + html)
                VscoLoader.mMediaUrls.clear()
                VscoLoader.mMediaUrls.addAll(downloadUrls)

                // 5. Update UI
                withContext(Dispatchers.Main) {
                    if (VscoLoader.mMediaUrls.isNotEmpty()) {
                        // Load thumbnail into ImageView
                        Glide.with(this@MainActivity)
                            .load(thumbUrl)
                            .centerCrop()
                            .into(ivPreview)

                        tvTitle.text = VscoLoader.mTitle
                        showPreviewUI()

                        // If shared intent, start download immediately
                        if (VscoLoader.isShared) {
                            startDownloadService()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "No media found", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading HTML", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to load link", Toast.LENGTH_SHORT).show()
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