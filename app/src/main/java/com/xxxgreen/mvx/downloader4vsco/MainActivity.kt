package com.xxxgreen.mvx.downloader4vsco

// IMPORT THE GENERATED BINDING CLASSES
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.android.billingclient.api.*
import com.bumptech.glide.Glide
import com.google.android.gms.ads.*
import com.google.firebase.analytics.FirebaseAnalytics
import com.xxxgreen.mvx.downloader4vsco.databinding.ActivityMainBinding
import com.xxxgreen.mvx.downloader4vsco.databinding.DialogRateBinding
import com.xxxgreen.mvx.downloader4vsco.databinding.DialogUpgradeBinding
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private val bannerIdTest = "ca-app-pub-3940256099942544/6300978111" // Test ID
    private val bannerIdReal = "ca-app-pub-7417392682402637/1939309490" // Real ID
    private val bannerId = bannerIdTest
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    // 1. Declare Binding Object
    private lateinit var binding: ActivityMainBinding

    private var fetchJob: Job? = null

    private lateinit var requestNotificationLauncher: androidx.activity.result.ActivityResultLauncher<String>

    // Logic Variables
    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:vsco\\.)|(?:vs\\.)?co\\/)", Pattern.CASE_INSENSITIVE)
    private var currentState: UIState = UIState.EMPTY

    // Billing Variables
    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null
    private val PRODUCT_ID = "vscoloader_gold"

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
            binding.btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE

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
                tvProgress.text = "$completed / $total"
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

                // Delay slightly to let the user see the success message, then close
                Handler(Looper.getMainLooper()).postDelayed({
                    finish() // Closes the app and removes from recents (optional) or just finish()
                }, 1000)
            } else {
                Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_SHORT).show()
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

        // 2. Initialize Firebase
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

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

            // --- CHAIN REACTION: NOW REQUEST BATTERY ---
            requestBatteryOptimization()
        }

        // Setup Utilities
        setupListeners()
        VscoLoader.prepareFileDirs()

        // Billing & Ads
        setupBilling()
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        val isGold = prefs.getBoolean("IS_GOLD", false)
        checkSubscriptionAndLoadAds(isGold)

        // check permissions
        startBackgroundPermissionChain()

        // Setup Toolbar
        updateUpgradeIcon(isGold)
        setupToolbarMenu()

        // Setup Webview
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

            // 2. Clear Input
            binding.etMainInput.setText("")

            // 3. Reset UI
            updateUI(UIState.EMPTY)

            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        }

        // Action Button
        binding.btnAction.setOnClickListener {
            if (currentState == UIState.PREVIEW) {
                startDownloadService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundMenuVisibility()

        // --- RESTORE STATE FIX ---
        // If we are currently showing "Empty" or "Loading", but we actually HAVE data,
        // force the UI back to the Preview state.
        if (currentState == UIState.EMPTY && VscoLoader.mMediaUrls.isNotEmpty()) {

            // 1. Restore the Title
            binding.tvTitle.text = VscoLoader.mTitle.ifEmpty { "Items Found" }

            // 2. Restore the Image (Use override to force size if view is not measured yet)
            if (isValidContextForGlide(this)) {
                Glide.with(this)
                    .load(VscoLoader.mMediaUrls[0])
                    .override(1000, 1000) // Force load even if view is GONE/0dp
                    .centerCrop()
                    .into(binding.ivPreview)
            }

            // 3. Force the UI visible
            updateUI(UIState.PREVIEW)
        }
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
                R.id.action_upgrade -> {
                    showUpgradeDialog()
                    true
                }
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

    private fun logInputEvent(eventName: String) {
        val inputValue = binding.etMainInput.text.toString()
        val bundle = Bundle().apply {
            putString("input_value", inputValue)
        }
        firebaseAnalytics.logEvent(eventName, bundle)
        Log.d("Analytics", "Logged event: $eventName with value: $inputValue")
    }

    // --- UPGRADE DIALOG (WITH BINDING) ---

    private fun incrementSuccessfulRuns() {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)

        // 1. Increment Counter
        val currentCount = prefs.getInt("SUCCESS_RUNS", 0) + 1
        prefs.edit().putInt("SUCCESS_RUNS", currentCount).apply()

        Log.d("MainActivity", "Successful Runs: $currentCount")

        // 2. Check if multiple of 6
        if (currentCount > 0 && currentCount % 6 == 0) {
            val cycle = currentCount / 6

            // Odd cycles (1, 3, 5... -> runs 6, 18, 30): Show Upgrade
            // Even cycles (2, 4, 6... -> runs 12, 24, 36): Show Rate
            if (cycle % 2 != 0) {
                // Check if user is already Gold before annoying them with Upgrade dialog
                val isGold = prefs.getBoolean("IS_GOLD", false)
                if (!isGold) {
                    showUpgradeDialog()
                }
            } else {
                showRateDialog()
            }
        }
    }

    private fun showRateDialog() {
        // Inflate the Rate Dialog layout
        val rateBinding = DialogRateBinding.inflate(layoutInflater)

        val builder = AlertDialog.Builder(this)
            .setView(rateBinding.root)
            .setCancelable(true)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // "Nah" Button -> Dismiss
        rateBinding.btnNah.setOnClickListener {
            dialog.dismiss()
        }

        // "Rate" Button (ID is btnUpgrade in your xml) -> Open Play Store
        rateBinding.btnUpgrade.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }

        dialog.show()
    }

    private fun showUpgradeDialog() {
        // Inflate the Dialog layout using Binding
        val dialogBinding = DialogUpgradeBinding.inflate(layoutInflater)

        val builder = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Bind Dialog Listeners directly
        //dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnNah.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnUpgrade.setOnClickListener {
            dialog.dismiss()
            launchBillingFlow()
        }

        dialog.show()
    }

    // --- LOGIC & UI UPDATES ---

    private fun handleInput(rawInput: String) {
        // 1. LOG ANALYTICS (Add this line)
        logInputEvent("handle_input")

        // cancels any delayed UI triggers
        inputHandler.removeCallbacksAndMessages(null)

        // 1. CANCEL ANY RUNNING FETCH
        fetchJob?.cancel()

        // 2. HIDE KEYBOARD
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMainInput.windowToken, 0)
        binding.etMainInput.clearFocus()

        // 3. FAST VALIDATION (Do this immediately)
        var input = rawInput.trim()
        if (input.contains("http://")) input = input.replace("http://", "https://")
        if (input.endsWith("/")) input = input.substring(0, input.length - 1)

        if (!VALID_INPUT_REGEX.matcher(input).find()) return

        // 4. INSTANT UI FEEDBACK (The Fix)
        // Show the spinner NOW. Do not wait for the keyboard.
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

            VscoLoader.resetVars()

            // Collection
            if (url.contains("/collection")) {
                if (!isGold) {
                    binding.etMainInput.setText("")
                    showUpgradeDialog()
                    updateUI(UIState.EMPTY) // Reset if failed
                    return@postDelayed
                }
                VscoLoader.isCollection = true
                val username = VscoLoader.extractUsernameFromUrl(url)
                if (username.isNotEmpty()) VscoLoader.mTitle = username
                url += "/1"
                loadInWebView(url)
            }
            // Profile
            else if (!url.contains("/media") && !url.contains("/video") && !url.contains("vs.co")) {
                if (!isGold) {
                    binding.etMainInput.setText("")
                    showUpgradeDialog()
                    updateUI(UIState.EMPTY) // Reset if failed
                    return@postDelayed
                }
                VscoLoader.isProfile = true
                val username = VscoLoader.extractUsernameFromUrl(url)
                if (username.isNotEmpty()) VscoLoader.mTitle = username
                url += "/gallery"
                loadInWebView(url)
            }
            // Shortlink / Media
            else if (input.contains("vs.co")) {
                loadInWebView(url)
            } else {
                loadMediaData(url)
            }
        }, 300)
    }

    private fun loadInWebView(url: String) {
        //updateUI(UIState.LOADING)
        binding.webView.loadUrl(url)
        // logic continues in setupWebView()
    }

    private var lastLoadedMediaId: String? = null

    fun onShortlinkResolved(resolvedUrl: String) {
        val mediaId = extractMediaId(resolvedUrl)

        // GUARD CLAUSE: If we just loaded this ID, stop here.
        if (mediaId != null && mediaId == lastLoadedMediaId) {
            Log.d("App", "Duplicate ID detected ($mediaId), skipping UI reset.")
            return
        }

        lastLoadedMediaId = mediaId

        // Proceed as normal
        //updateUI(UIState.LOADING)

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
        binding.webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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

                        // --- CHANGED THIS LINE ---
                        // Old: loadMediaData(url)
                        // New: Calls your smart function with the duplicate check
                        onShortlinkResolved(url)
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()

                if (url.contains("medias/profile") || url.contains("medias/videos")) {
                    Log.d("MainActivity", "Intercepted API: $url")

                    val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
                    val isGold = prefs.getBoolean("IS_GOLD", false)

                    if (!isGold) {
                        Log.d("MainActivity", "Blocked Profile Download (Non-Gold)")
                        // Stop Loading and Show Dialog
                        runOnUiThread {
                            updateUI(UIState.EMPTY)
                            showUpgradeDialog()
                        }
                        // Return without starting the fetch job
                        return super.shouldInterceptRequest(view, request)
                    }

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

                                    /*
                                    // SHOW UI (Without Button)
                                    if (binding.previewCard.visibility != View.VISIBLE) {
                                        binding.previewCard.fadeIn()
                                        binding.bottomControlCard.fadeIn()

                                        // CRITICAL FIX: Explicitly hide the button while loading
                                        binding.btnAction.visibility = View.INVISIBLE

                                        binding.layoutLoading.fadeOut()
                                    }
                                    */

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
        // Cancel any previous jobs (important for rapid pasting)
        fetchJob?.cancel()

        //updateUI(UIState.LOADING)

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

                            // --- FIX START ---
                            // Prevent the "Double Update" bug.
                            // If we are auto-downloading, skip PREVIEW and go straight to DOWNLOADING.
                            if (VscoLoader.isShared) {
                                startDownloadService() // This triggers updateUI(DOWNLOADING)
                            } else {
                                updateUI(UIState.PREVIEW) // This triggers updateUI(PREVIEW)
                            }
                            // --- FIX END ---

                        } else {
                            Toast.makeText(this@MainActivity, "No media found", Toast.LENGTH_SHORT).show()
                            updateUI(UIState.EMPTY)
                        }
                    }
                }
            } catch (e: Exception) {
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
        updateUI(UIState.DOWNLOADING)
        val intent = Intent(this, DownloadService::class.java)
        intent.action = "START_SERVICE"
        startService(intent)
    }

    private fun updateUI(state: UIState) {
        Log.d("MainActivity", "UpdateUI to $state")
        currentState = state

        // 1. DISABLE TRANSITION MANAGER
        // It causes race conditions with the keyboard closing.
        // We will manage visibility manually.
        // androidx.transition.TransitionManager.beginDelayedTransition(binding.root)

        // 2. Safety Reset (Ensure inputs are clickable)
        binding.etMainInput.isEnabled = true
        binding.btnPaste.isEnabled = true
        binding.btnPaste.alpha = 1.0f

        when (state) {
            UIState.EMPTY -> {
                binding.layoutLoading.visibility = View.GONE
                binding.previewCard.visibility = View.GONE
                binding.bottomControlCard.visibility = View.GONE
                binding.overlayDownloading.visibility = View.GONE
            }
            UIState.LOADING -> {
                // Manually show Loading, Hide others
                binding.layoutLoading.alpha = 1.0f
                binding.layoutLoading.visibility = View.VISIBLE

                binding.previewCard.visibility = View.GONE
                binding.bottomControlCard.visibility = View.GONE
                binding.overlayDownloading.visibility = View.GONE

                // Dim input to show it's busy
                binding.etMainInput.isEnabled = false
                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.3f
            }
            UIState.PREVIEW -> {
                binding.layoutLoading.visibility = View.GONE

                // Force visibility immediately
                binding.previewCard.alpha = 1.0f
                binding.previewCard.visibility = View.VISIBLE

                binding.bottomControlCard.alpha = 1.0f
                binding.bottomControlCard.visibility = View.VISIBLE

                binding.overlayDownloading.visibility = View.GONE

                binding.btnAction.setImageResource(R.drawable.ic_download)
                binding.btnAction.isEnabled = true
                binding.btnAction.visibility = View.VISIBLE
            }
            UIState.DOWNLOADING -> {
                binding.layoutLoading.visibility = View.GONE

                binding.previewCard.alpha = 1.0f
                binding.previewCard.visibility = View.VISIBLE

                binding.bottomControlCard.alpha = 1.0f
                binding.bottomControlCard.visibility = View.VISIBLE

                binding.overlayDownloading.visibility = View.VISIBLE

                // Hide action button
                binding.btnAction.visibility = View.INVISIBLE

                // Lock input
                binding.etMainInput.isEnabled = false
                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.3f
            }
            UIState.FINISHED -> {
                binding.layoutLoading.visibility = View.GONE

                binding.previewCard.alpha = 1.0f
                binding.previewCard.visibility = View.VISIBLE

                binding.bottomControlCard.alpha = 1.0f
                binding.bottomControlCard.visibility = View.VISIBLE

                binding.overlayDownloading.visibility = View.GONE

                binding.btnAction.setImageResource(R.drawable.ic_check)
                binding.btnAction.isEnabled = false
                binding.btnAction.visibility = View.VISIBLE

                logInputEvent("download_finished")
                incrementSuccessfulRuns()
            }
        }
    }

    // --- BILLING LOGIC ---

    private fun setupBilling() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        startBillingConnection()
    }

    private fun startBillingConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryActivePurchases()
                }
            }
            override fun onBillingServiceDisconnected() { }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        ) { billingResult, detailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK
                && detailsResult.productDetailsList.isNotEmpty()
            ) {
                productDetails = detailsResult.productDetailsList[0]
            }
        }
    }

    private fun queryActivePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isGold = false
                for (purchase in purchases) {
                    if (purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isGold = true
                        if (!purchase.isAcknowledged) handlePurchase(purchase)
                    }
                }
                saveGoldStatus(isGold)
            }
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) handlePurchase(purchase)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        runOnUiThread {
                            Toast.makeText(this, "Thank you for your support <3", Toast.LENGTH_SHORT).show()
                            saveGoldStatus(true)
                            recreate()
                        }
                    }
                }
            } else {
                saveGoldStatus(true)
            }
        }
    }

    private fun saveGoldStatus(isGold: Boolean) {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("IS_GOLD", isGold).apply()
        checkSubscriptionAndLoadAds(isGold)
        runOnUiThread { updateUpgradeIcon(isGold) }
    }

    private fun checkSubscriptionAndLoadAds(isGold: Boolean) {
        if (!isGold) {
            initAdMob()
        } else {
            binding.adContainer.removeAllViews()
            binding.adContainer.visibility = View.GONE
        }
    }

    private fun launchBillingFlow() {
        if (productDetails != null) {
            val offerToken = productDetails!!.subscriptionOfferDetails?.get(0)?.offerToken ?: ""
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails!!)
                    .setOfferToken(offerToken)
                    .build()
            )
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
            billingClient.launchBillingFlow(this, billingFlowParams)
        } else {
            Toast.makeText(this, "Billing not ready yet.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUpgradeIcon(isGold: Boolean) {
        val upgradeItem = binding.toolbar.menu.findItem(R.id.action_upgrade)
        if (upgradeItem != null) {
            if (isGold) {
                upgradeItem.icon?.setTint(Color.parseColor("#FFD700"))
                upgradeItem.isEnabled = false
            } else {
                upgradeItem.icon?.setTintList(null)
                upgradeItem.isEnabled = true
            }
        }
    }

    private fun initAdMob() {
        MobileAds.initialize(this) {}
        val adView = AdView(this)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = bannerId
        binding.adContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
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

                // 4. Manual Trigger (Only one download starts)
                handleInput(sharedText)
            }
        }
    }

    // --- ANIMATION HELPERS ---

    private fun View.fadeIn(duration: Long = 200) {
        // If already visible and opaque, do nothing
        if (visibility == View.VISIBLE && alpha == 1f) return

        // Cancel any ongoing animation
        animate().cancel()

        // Prepare view
        alpha = 0f
        visibility = View.VISIBLE

        // Animate
        animate()
            .alpha(1f)
            .setDuration(duration)
            .withEndAction(null) // Clear any old end actions
            .start()
    }

    private fun View.fadeOut(targetVisibility: Int = View.GONE, duration: Long = 100) {
        // If already in the target state, do nothing
        if (visibility == targetVisibility && alpha == 0f) return

        animate().cancel()

        animate()
            .alpha(0f)
            .setDuration(duration)
            .withEndAction {
                visibility = targetVisibility
            }
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(finishReceiver)
        unregisterReceiver(progressReceiver)
    }
}