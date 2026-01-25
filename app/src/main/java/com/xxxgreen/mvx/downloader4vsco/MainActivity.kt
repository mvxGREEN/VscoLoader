package com.xxxgreen.mvx.downloader4vsco

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.gms.ads.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    // --- UI Components ---
    private lateinit var etInput: EditText
    private lateinit var btnClear: ImageButton
    private lateinit var btnPaste: ImageButton
    private lateinit var layoutLoading: LinearLayout
    private lateinit var previewCard: CardView
    private lateinit var ivPreview: ImageView
    private lateinit var overlayDownloading: FrameLayout
    private lateinit var bottomControlCard: CardView
    private lateinit var tvTitle: TextView
    private lateinit var btnAction: ImageButton
    private lateinit var webView: WebView
    private lateinit var adContainer: FrameLayout

    // --- Logic Variables ---
    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:vsco\\.)|(?:vs\\.)?co\\/)", Pattern.CASE_INSENSITIVE)

    enum class UIState {
        EMPTY, LOADING, PREVIEW, DOWNLOADING, FINISHED
    }

    // --- Receiver for Download Completion ---
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Download finished broadcast received")
            updateUI(UIState.FINISHED)
            Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize Views
        bindViews()

        // 2. Setup Utilities
        VscoLoader.prepareFileDirs()
        initAdMob()
        checkPermissions()
        setupWebView()

        // 3. Register Receiver (API 33+ Compatible)
        val filter = IntentFilter("DOWNLOAD_FINISHED_ACTION")
        ContextCompat.registerReceiver(this, finishReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // 4. Initial State
        updateUI(UIState.EMPTY)
        handleSharedIntent()
    }

    private fun bindViews() {
        etInput = findViewById(R.id.etMainInput)
        btnClear = findViewById(R.id.btnClear)
        btnPaste = findViewById(R.id.btnPaste)
        layoutLoading = findViewById(R.id.layoutLoading)
        previewCard = findViewById(R.id.previewCard)
        ivPreview = findViewById(R.id.ivPreview)
        overlayDownloading = findViewById(R.id.overlayDownloading)
        bottomControlCard = findViewById(R.id.bottomControlCard)
        tvTitle = findViewById(R.id.tvTitle)
        btnAction = findViewById(R.id.btnAction)
        webView = findViewById(R.id.webView)
        adContainer = findViewById(R.id.adContainer)

        setupListeners()
    }

    private fun setupListeners() {
        // Paste Button
        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                etInput.setText(text)
                handleInput(text)
            }
        }

        // Clear Button
        btnClear.setOnClickListener {
            etInput.setText("")
            VscoLoader.resetVars()
            updateUI(UIState.EMPTY)
        }

        // Action Button (Download / Checkmark)
        btnAction.setOnClickListener {
            // Only action is "Download" when in PREVIEW state
            //if (btnAction.drawable.constantState == ContextCompat.getDrawable(this, R.drawable.ic_download)?.constantState) {
            //    startDownloadService()
            //}
            startDownloadService()
        }

        // Input Listener (Paste Detection)
        etInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val lengthDiff = count - before
                if (lengthDiff > 1) {
                    handleInput(s.toString())
                } else if (s.isNullOrEmpty()) {
                    updateUI(UIState.EMPTY)
                }
            }
        })
    }

    // --- Core Logic ---

    private fun handleInput(rawInput: String) {
        var input = rawInput.trim()

        // Basic Cleanup
        if (input.contains("http://")) input = input.replace("http://", "https://")
        if (input.endsWith("/")) input = input.substring(0, input.length - 1)

        // Regex Validation
        if (!VALID_INPUT_REGEX.matcher(input).find()) {
            return
        }

        // Extract URL
        var url = "https://"
        if (input.contains("vs.co")) url += input.substring(input.indexOf("vs.co"))
        else if (input.contains("vsco.co")) url += input.substring(input.indexOf("vsco.co"))
        else url = input

        // Reset previous data
        VscoLoader.resetVars()

        // Check Type: Profile vs Media
        if (url.contains("/collection")) {
            VscoLoader.isCollection = true
            url += "/1"
            loadInWebView(url)
        } else if (!url.contains("/media") && !url.contains("/video") && !url.contains("vs.co")) {
            VscoLoader.isProfile = true
            url += "/gallery"
            loadInWebView(url)
        } else {
            loadMediaData(url)
        }
    }

    private fun loadInWebView(url: String) {
        updateUI(UIState.LOADING)
        webView.loadUrl(url) // The WebViewClient below handles the rest
    }

    private fun loadMediaData(url: String) {
        updateUI(UIState.LOADING)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Jsoup Scraping
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()

                val html = doc.html()
                val head = doc.head().html()

                // Extract Metadata
                var title = doc.title()
                if (title.contains("|")) title = title.substringBefore("|")
                title = title.trim().replace(" ", "")
                VscoLoader.mTitle = title

                val thumbUrl = VscoLoader.extractThumbnail(url, head + html)
                val downloadUrls = VscoLoader.extractDownloadUrls(url, head + html)

                VscoLoader.mMediaUrls.clear()
                VscoLoader.mMediaUrls.addAll(downloadUrls)

                // Update UI
                withContext(Dispatchers.Main) {
                    if (VscoLoader.mMediaUrls.isNotEmpty()) {
                        tvTitle.text = VscoLoader.mTitle
                        Glide.with(this@MainActivity).load(thumbUrl).centerCrop().into(ivPreview)

                        updateUI(UIState.PREVIEW)

                        if (VscoLoader.isShared) {
                            startDownloadService()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "No media found", Toast.LENGTH_SHORT).show()
                        updateUI(UIState.EMPTY)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Load Failed", Toast.LENGTH_SHORT).show()
                    updateUI(UIState.EMPTY)
                }
            }
        }
    }

    private fun startDownloadService() {
        updateUI(UIState.DOWNLOADING)
        val intent = Intent(this, DownloadService::class.java)
        intent.action = "START_SERVICE"
        startService(intent)
    }

    // --- UI State Management ---

    private fun updateUI(state: UIState) {
        when (state) {
            UIState.EMPTY -> {
                layoutLoading.visibility = View.GONE
                previewCard.visibility = View.GONE
                bottomControlCard.visibility = View.GONE
            }
            UIState.LOADING -> {
                layoutLoading.visibility = View.VISIBLE
                previewCard.visibility = View.GONE
                bottomControlCard.visibility = View.GONE
            }
            UIState.PREVIEW -> {
                layoutLoading.visibility = View.GONE
                previewCard.visibility = View.VISIBLE
                overlayDownloading.visibility = View.GONE
                bottomControlCard.visibility = View.VISIBLE

                btnAction.visibility = View.VISIBLE
                btnAction.setImageResource(R.drawable.ic_download)
                btnAction.isEnabled = true
            }
            UIState.DOWNLOADING -> {
                layoutLoading.visibility = View.GONE
                previewCard.visibility = View.VISIBLE
                overlayDownloading.visibility = View.VISIBLE
                bottomControlCard.visibility = View.VISIBLE

                btnAction.visibility = View.INVISIBLE
            }
            UIState.FINISHED -> {
                layoutLoading.visibility = View.GONE
                previewCard.visibility = View.VISIBLE
                overlayDownloading.visibility = View.GONE
                bottomControlCard.visibility = View.VISIBLE

                btnAction.visibility = View.VISIBLE
                btnAction.setImageResource(R.drawable.ic_check)
                btnAction.isEnabled = false
            }
        }
    }

    // --- WebView Client (Profile Interceptor) ---
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()
                if (url.contains("medias/profile")) {
                    // Logic from MWebViewClient.cs
                    // Trigger recursive profile request here using OkHttp or recursive logic
                    // For now, let's assume simple interception or add your full recursive logic
                    Log.d("MainActivity", "Intercepted profile request: $url")
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    // --- Boilerplate (AdMob, Intents, Permissions) ---
    private fun initAdMob() {
        MobileAds.initialize(this) {}
        val adView = AdView(this)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
        adContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                VscoLoader.isShared = true
                etInput.setText(text)
                handleInput(text)
            }
        }
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