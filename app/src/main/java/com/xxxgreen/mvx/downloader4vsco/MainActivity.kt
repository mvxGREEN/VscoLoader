package com.xxxgreen.mvx.downloader4vsco

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.xxxgreen.mvx.downloader4vsco.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var fetchJob: Job? = null

    private lateinit var requestNotificationLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var requestWritePermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:vsco\\.)|(?:vs\\.)?co\\/)", Pattern.CASE_INSENSITIVE)
    private var currentState: UIState = UIState.EMPTY

    private var lastLoadedUrl = ""

    private val inputHandler = Handler(Looper.getMainLooper())
    private val inputRunnable = Runnable {
        val text = binding.etMainInput.text.toString()
        // Only trigger if it actually looks like a VSCO URL to prevent random searches
        if (VALID_INPUT_REGEX.matcher(text).find()) {
            handleInput(text)
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            binding.btnClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE

            // 1. Cancel previous pending search (Debounce)
            inputHandler.removeCallbacks(inputRunnable)

            if (s.isNullOrEmpty()) {
                updateUI(UIState.EMPTY)
            } else {
                // 2. Wait 1 second. If user stops typing, trigger load.
                // This replaces the faulty "lengthDiff" logic.
                inputHandler.postDelayed(inputRunnable, 1000)
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // Logic moved to afterTextChanged for safer handling
        }
    }

    enum class UIState {
        EMPTY, LOADING, PREVIEW, DOWNLOADING, FINISHED
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val completed = intent?.getIntExtra("completed", 0) ?: 0
            val total = intent?.getIntExtra("total", 0) ?: 0

            if (total > 0) {
                // Update determinate progress bar
                val progressBar = findViewById<ProgressBar>(R.id.pbOverlay)
                progressBar.isIndeterminate = false
                progressBar.max = total
                progressBar.progress = completed

                val tvProgress = findViewById<TextView>(R.id.tvOverlayProgress)
                if (total == 1) tvProgress.text = "Downloading…"
                else tvProgress.text = "$completed / $total items"
            }
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Download finished broadcast received")
            updateUI(UIState.FINISHED)

            // Check if this session was single & initiated via Share
            if (VscoLoader.isShared && !VscoLoader.isProfile && !VscoLoader.isCollection) {
                Toast.makeText(context, "Saved!", Toast.LENGTH_LONG).show()

                // reset shared flag
                VscoLoader.isShared = false

                binding.etMainInput.setText("")
                updateUI(UIState.EMPTY)

                // Delay slightly to let the user see the success message, then close
                Handler(Looper.getMainLooper()).postDelayed({
                    finish() // Closes the app and removes from recents (optional) or just finish()
                }, 333)
            } else {
                Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // NUKE old state
        VscoLoader.resetVars()
        VscoLoader.isShared = false 

        // 2. Inflate Layout via Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- 1. INITIALIZE PERMISSION LAUNCHER ---
        requestNotificationLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            // This block runs immediately after the user clicks Allow/Deny
            if (isGranted) {
                Log.d("MainActivity", "Notifications granted")
            } else {
                Toast.makeText(this, "Notifications are recommended for background downloads", Toast.LENGTH_SHORT).show()
            }
        }

        requestWritePermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted, retry the download
                startDownloadService()
            } else {
                Toast.makeText(this, "Storage permission is required on this Android version to save files.", Toast.LENGTH_LONG).show()
                // Reset UI so they can try clicking the button again
                if (currentState == UIState.DOWNLOADING || currentState == UIState.LOADING) {
                    updateUI(UIState.PREVIEW)
                }
            }
        }

        setupListeners()
        VscoLoader.prepareFileDirs()

        // check permissions
        startBackgroundPermissionChain()

        // Setup UI
        setupToolbarMenu()
        setupWebView()

        // Finish Receiver
        val filter = IntentFilter("DOWNLOAD_FINISHED_ACTION")
        ContextCompat.registerReceiver(this, finishReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED)

        // Progress Receiver
        val progressFilter = IntentFilter(DownloadService.PROGRESS_UPDATE_ACTION)
        ContextCompat.registerReceiver(this, progressReceiver, progressFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Initial State
        updateUI(UIState.EMPTY)

        // check for shared url
        checkIntent(intent)
    }

    // --- VIEW BINDING SETUP ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        // enter button listener
        binding.etMainInput.setOnEditorActionListener { v, actionId, event ->
            val text = v.text.toString()
            handleInput(text)
            true
        }

        // Text Watcher
        binding.etMainInput.addTextChangedListener(textWatcher)

        // Paste button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()

                // 1. Set the text
                // (This triggers the TextWatcher, which schedules a check 1 second later)
                binding.etMainInput.setText(text)

                // 2. CANCEL the 1-second wait immediately
                // We know the user is done "typing" because they just pasted.
                inputHandler.removeCallbacks(inputRunnable)

                // 3. Force Instant Load
                handleInput(text)
            }
        }

        // Clear Button (UPDATED)
        binding.btnClear.setOnClickListener {
            // 1. STOP EVERYTHING
            fetchJob?.cancel()          // Stops the scraping/loading (Coroutines)
            VscoLoader.cancelBatch(this) // Stops the downloading (Service/Receiver)
            inputHandler.removeCallbacks(inputRunnable) // Stops pending debounce

            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank") // Optional: clear the visual state
            lastLoadedUrl = ""
            lastLoadedMediaId = ""

            // 2. Clear Input
            binding.etMainInput.setText("")

            // 3. Reset UI
            updateUI(UIState.EMPTY)

            //Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        }

        // Action Button
        binding.btnAction.setOnClickListener {
            if (currentState == UIState.PREVIEW) {
                startDownloadService()
            }
        }

        // share button
        binding.btnShare.setOnClickListener {
            shareDownloadedFile()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundMenuVisibility()


    }

    private fun updateBackgroundMenuVisibility() {
        val item = binding.toolbar.menu.findItem(R.id.action_enable_background) ?: return
        // Show item ONLY if permissions are missing
        item.isVisible = !hasBackgroundPermissions()
    }

    private fun startBackgroundPermissionChain() {
        // STEP 1: Check Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Request Notifs -> The 'requestNotificationLauncher' callback will handle Step 2
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // If we already have notifications (or are on Android < 13), jump straight to Step 2
        requestBatteryOptimization()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        // STEP 2: Check Battery Optimization (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open background settings", Toast.LENGTH_SHORT).show()
                }
            } else {
                //Toast.makeText(this, "Background setup complete!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasBackgroundPermissions(): Boolean {
        // 1. Check Notification Permission (Android 13+)
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required below Android 13
        }

        // 2. Check Battery Optimization (Android 6+)
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Not required below Android 6
        }

        return notificationGranted && batteryIgnored
    }

    private fun requestBackgroundPermissions() {
        // Priority 1: Notifications (Async)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                // We return here because we can't show two dialogs at once.
                // The user will likely click "Enable Background" again if Battery is also missing.
                return
            }
        }

        // Priority 2: Battery Optimizations (Intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    @SuppressLint("BatteryLife") // Suppress warning, we have a valid use case
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 2. UPDATE: setupToolbarMenu to handle the click
    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_privacy -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/privacy-policy"))
                    startActivity(intent)
                    true
                }
                R.id.action_about -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/"))
                    startActivity(intent)
                    true
                }
                // NEW CASE
                R.id.action_enable_background -> {
                    startBackgroundPermissionChain()
                    true
                }
                else -> false
            }
        }
    }

    // --- LOGIC & UI UPDATES ---

    private fun handleInput(rawInput: String) {
        // cancels any delayed UI triggers
        inputHandler.removeCallbacksAndMessages(null)

        // 1. CANCEL ANY RUNNING FETCH
        fetchJob?.cancel()
        binding.webView.stopLoading()

        // Prevent the guard clause from blocking consecutive loads
        lastLoadedUrl = ""
        lastLoadedMediaId = null

        // Clear the webview state
        binding.webView.loadUrl("about:blank")
        binding.webView.clearCache(true)
        binding.webView.clearHistory()

        // Clear System Web Storage (Cookies & DOM)
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()

        VscoLoader.resetVars()

        // 2. HIDE KEYBOARD
        val imm =
            getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMainInput.windowToken, 0)
        binding.etMainInput.clearFocus()

        // 3. FAST VALIDATION (Do this immediately)
        var input = rawInput.trim()
        // Extract strictly the URL chunk by splitting on whitespace.
        // This strips out "Check out my pic! " from Share Intents.
        input = input.split("\\s+".toRegex()).firstOrNull {
            it.contains("vs.co") || it.contains("vsco.co")
        } ?: input

        if (input.contains("http://")) input = input.replace("http://", "https://")
        if (input.endsWith("/")) input = input.substring(0, input.length - 1)

        if (!VALID_INPUT_REGEX.matcher(input).find()) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            return
        }

        // 4. INSTANT UI FEEDBACK
        updateUI(UIState.LOADING)

        // 5. DELAYED PROCESSING
        // We still wait 300ms to start the heavy network/webview work.
        // This prevents the "Layout Thrashing" glitch, but the user
        // already sees the "Loading" spinner, so it feels instant.
        Handler(Looper.getMainLooper()).postDelayed({
            var url = "https://"
            if (input.contains("vs.co")) url += input.substring(input.indexOf("vs.co"))
            else if (input.contains("vsco.co")) url += input.substring(input.indexOf("vsco.co"))
            else url = input

            val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
            val isGold = prefs.getBoolean("IS_GOLD", false)

            // Collection
            if (url.contains("/collection")) {
                VscoLoader.isCollection = true
                val username = VscoLoader.extractUsernameFromUrl(url)
                if (username.isNotEmpty()) VscoLoader.mTitle = username
                url += "/1"
                loadInWebView(url)
            }
            // Profile
            else if (!url.contains("/media") && !url.contains("/video") && !url.contains("vs.co")) {
                VscoLoader.isProfile = true
                val username = VscoLoader.extractUsernameFromUrl(url)
                if (username.isNotEmpty()) VscoLoader.mTitle = username
                url += "/gallery"
                loadInWebView(url)
            }
            // Shortlink / Media
            else if (input.contains("vs.co")) {
                Log.d("MainActivity", "Shortlink loading in webview: $url")
                loadInWebView(url)
            } else {
                Log.d("MainActivity", "Link loading in webview: $url")
                loadMediaData(url)
            }
        }, 600)
    }

    private fun loadInWebView(url: String) {
        //updateUI(UIState.LOADING)
        binding.webView.loadUrl(url)
        // logic continues in setupWebView()
    }

    private var lastLoadedMediaId: String? = null

    fun onShortlinkResolved(resolvedUrl: String) {
        Log.i("VscoLoader", "onShortlinkResolved: $resolvedUrl")
        val mediaId = extractMediaId(resolvedUrl)

        // GUARD CLAUSE: If we just loaded this ID, stop here.
        if (mediaId != null && mediaId == lastLoadedMediaId) {
            Log.d("App", "Duplicate ID detected ($mediaId), skipping UI reset.")
            return
        }

        lastLoadedMediaId = mediaId

        loadMediaData(resolvedUrl)
    }

    // Helper function to grab the ID from the VSCO URL
    private fun extractMediaId(url: String): String? {
        // URL looks like: .../media/567c1ce3...?share=...
        if (!url.contains("/media/")) return null

        return url.substringAfter("/media/") // Get everything after /media/
            .substringBefore("?")      // Stop at the query params
            .substringBefore("/")      // Stop if there are trailing slashes
    }

    private fun setupWebView() {
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        binding.webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // 5. FIX: PREVENT DUPLICATE REDIRECTS
                if (url != null && url != lastLoadedUrl) {
                    lastLoadedUrl = url

                    val username = VscoLoader.extractUsernameFromUrl(url)
                    if (username.isNotEmpty() && username != "api") {
                        VscoLoader.mTitle = username
                    }

                    if (url.contains("/media/") || url.contains("/video/")) {
                        Log.d("MainActivity", "Shortlink resolved to Media: $url")
                        onShortlinkResolved(url)
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()

                if (url.contains("medias/profile") || url.contains("medias/videos")) {
                    Log.d("MainActivity", "Intercepted API: $url")

                    val headers = request?.requestHeaders ?: emptyMap()
                    val cookie = CookieManager.getInstance().getCookie(url) ?: ""

                    fetchJob = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            VscoLoader.processProfile(url, cookie, headers) { count ->

                                runOnUiThread {
                                    // Title = Username (already stored in mTitle during handleInput)
                                    binding.tvTitle.text = VscoLoader.mTitle

                                    // Subtitle = Item Count
                                    binding.tvSubtitle.text = "$count Items"

                                    if (count > 0 && isValidContextForGlide(this@MainActivity)) {
                                        Glide.with(this@MainActivity)
                                            .load(VscoLoader.mMediaUrls[0])
                                            // force it to load a large image
                                            .override(1000, 1000)
                                            .fitCenter()
                                            .into(binding.ivPreview)
                                    }
                                }
                            }

                            if (isActive) {
                                withContext(Dispatchers.Main) {
                                    if (VscoLoader.mMediaUrls.isNotEmpty()) {
                                        // Final Update to ensure consistency
                                        binding.tvTitle.text = VscoLoader.mTitle
                                        binding.tvSubtitle.text = "${VscoLoader.mMediaUrls.size} Items"
                                        // This function will finally show the button!
                                        updateUI(UIState.PREVIEW)
                                    } else {
                                        Toast.makeText(this@MainActivity, "No items found", Toast.LENGTH_SHORT).show()
                                        updateUI(UIState.EMPTY)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Profile fetch failed", e)

                            logErrorEvent("vl_error_profile", e)

                            if (isActive) {
                                withContext(Dispatchers.Main) { updateUI(UIState.EMPTY) }
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    // Helper to prevent Glide crashes if activity is destroyed
    private fun isValidContextForGlide(context: Context?): Boolean {
        if (context == null) return false
        if (context is Activity) {
            return !context.isDestroyed && !context.isFinishing
        }
        return true
    }

    private fun loadMediaData(url: String) {
        Log.i("VscoLoader", "loadMediaData: $url")
        // Cancel any previous jobs (important for rapid pasting)
        fetchJob?.cancel()

        fetchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get()

                // Title Extraction
                val rawTitle = doc.title()
                var finalTitle = "vsco_media"
                val parts = rawTitle.split("|")
                if (parts.size >= 2) {
                    if (parts.size >= 3) finalTitle = parts[parts.size - 2].trim()
                    else finalTitle = parts[0].trim()
                } else {
                    finalTitle = rawTitle.trim()
                }
                finalTitle = finalTitle.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_\\-]"), "")
                if (finalTitle.isEmpty()) finalTitle = "vsco_download"
                VscoLoader.mTitle = finalTitle

                val html = doc.html()
                val head = doc.head().html()

                val thumbUrl = VscoLoader.extractThumbnail(url, head + html)
                val downloadUrls = VscoLoader.extractDownloadUrls(url, head + html)

                VscoLoader.mMediaUrls.clear()
                VscoLoader.mMediaUrls.addAll(downloadUrls)

                if (isActive) {
                    withContext(Dispatchers.Main) {
                        if (VscoLoader.mMediaUrls.isNotEmpty()) {

                            // Title = Username (or page title)
                            binding.tvTitle.text = VscoLoader.mTitle

                            // Subtitle = 1 Item
                            binding.tvSubtitle.text = "1 Item"

                            if (isValidContextForGlide(this@MainActivity)) {
                                Glide.with(this@MainActivity)
                                    .load(thumbUrl)
                                    .centerCrop()
                                    .into(binding.ivPreview)
                            }

                            updateUI(UIState.PREVIEW)

                        } else {
                            Toast.makeText(this@MainActivity, "No media found", Toast.LENGTH_SHORT).show()
                            updateUI(UIState.EMPTY)
                        }
                    }
                }
            } catch (e: Exception) {
                logErrorEvent("vl_error_loading", e)

                if (isActive) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Load Failed", Toast.LENGTH_SHORT).show()
                        updateUI(UIState.EMPTY)
                    }
                }
            }
        }
    }

    private fun startDownloadService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // Permission missing: Request it and STOP here.
                requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }

        updateUI(UIState.DOWNLOADING)
        val intent = Intent(this, DownloadService::class.java)
        intent.action = "START_SERVICE"
        startService(intent)
    }

    private fun updateUI(state: UIState) {
        Log.d("MainActivity", "UpdateUI to $state")
        currentState = state

        // 1. LOG FIREBASE EVENTS BASED ON STATE
        val eventName = when (state) {
            UIState.LOADING -> "vl_ui_loading"
            UIState.PREVIEW -> "vl_ui_preview"
            UIState.DOWNLOADING -> "vl_ui_downloading"
            UIState.FINISHED -> "vl_ui_finish"
            UIState.EMPTY -> null // Usually no need to log empty/reset state
        }

        // --- Rest of your existing UI logic ---
        binding.etMainInput.isEnabled = true
        binding.btnPaste.isEnabled = true

        when (state) {
            UIState.EMPTY -> {
                binding.layoutLoading.visibility = View.INVISIBLE
                binding.previewCard.visibility = View.INVISIBLE
                binding.bottomControlCard.visibility = View.INVISIBLE
                binding.overlayDownloading.visibility = View.INVISIBLE
                binding.btnShare.visibility = View.INVISIBLE
            }
            UIState.LOADING -> {
                binding.layoutLoading.alpha = 1.0f
                binding.layoutLoading.visibility = View.VISIBLE
                binding.previewCard.visibility = View.INVISIBLE
                binding.bottomControlCard.visibility = View.INVISIBLE
                binding.overlayDownloading.visibility = View.INVISIBLE
                binding.etMainInput.isEnabled = false
                binding.btnShare.visibility = View.INVISIBLE
            }
            UIState.PREVIEW -> {
                binding.layoutLoading.visibility = View.INVISIBLE
                binding.previewCard.visibility = View.VISIBLE
                binding.bottomControlCard.visibility = View.VISIBLE
                binding.btnAction.visibility = View.VISIBLE
                binding.btnAction.isEnabled = true
                binding.btnAction.setImageResource(R.drawable.ic_download)
                binding.btnShare.visibility = View.INVISIBLE


            }
            UIState.DOWNLOADING -> {
                binding.overlayDownloading.visibility = View.VISIBLE
                binding.btnAction.visibility = View.INVISIBLE
                binding.etMainInput.isEnabled = false
                binding.btnShare.visibility = View.INVISIBLE
            }
            UIState.FINISHED -> {
                binding.overlayDownloading.visibility = View.INVISIBLE
                binding.btnAction.setImageResource(R.drawable.ic_check)
                binding.btnAction.isEnabled = false
                binding.btnAction.visibility = View.VISIBLE
                binding.btnShare.visibility = View.VISIBLE
            }
        }

        binding.root.post {
            binding.root.requestLayout()
            binding.root.invalidate()
        }
    }

    // Since launchMode is singleInstance, new shares will call this if app is already open
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent reference
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                Log.d("MainActivity", "Received Shared Intent: $sharedText")

                VscoLoader.isShared = true

                binding.etMainInput.removeTextChangedListener(textWatcher)
                binding.etMainInput.setText(sharedText)
                binding.etMainInput.addTextChangedListener(textWatcher)

                // Manual Trigger (Only one download starts)
                handleInput(sharedText)
            }
        }
    }

    private fun shareDownloadedFile() {
        val file = getDownloadedFile()

        if (file != null && file.exists()) {
            try {
                // Generate a secure content:// URI using FileProvider
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (file.extension == "mp4") "video/mp4" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, "Share to..."))
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sharing file", e)
                Toast.makeText(this, "Unable to share file.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDownloadedFile(): File? {
        // 1. Check if it's a concatenated video playlist file (m3u8/ts)
        if (VscoLoader.mFilePath.isNotEmpty()) {
            return File(VscoLoader.mFilePath)
        }

        // 2. Check for standard image/video download
        val isVideo = VscoLoader.mThumbnailFilename.contains("mp4") || !VscoLoader.mThumbnailFilename.contains("jpg")
        val folderPath = if (isVideo) VscoLoader.absPathMovies else VscoLoader.absPathPictures
        val extension = if (isVideo) ".mp4" else ".jpg"

        val filePath = folderPath + VscoLoader.mTitle + extension
        return File(filePath)
    }

    private fun logErrorEvent(eventName: String, error: Exception) {
        // (optional) call analytics here
        Log.e("Analytics", "Logged Error: $eventName", error)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(finishReceiver)
        unregisterReceiver(progressReceiver)
    }
}