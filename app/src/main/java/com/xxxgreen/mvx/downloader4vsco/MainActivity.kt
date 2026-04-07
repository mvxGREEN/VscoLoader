package com.xxxgreen.mvx.downloader4vsco

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
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.firebase.analytics.FirebaseAnalytics
import com.xxxgreen.mvx.downloader4vsco.databinding.ActivityMainBinding
import com.xxxgreen.mvx.downloader4vsco.databinding.DialogRateBinding
import com.xxxgreen.mvx.downloader4vsco.databinding.DialogUpgradeBinding
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private val interstitialIdTest = "ca-app-pub-3940256099942544/1033173712"
    private val interstitialIdReal = "ca-app-pub-7417392682402637/3359673540"
    //private val bannerIdTest = "ca-app-pub-3940256099942544/6300978111" // Test ID
    //private val bannerIdReal = "ca-app-pub-7417392682402637/1939309490" // Real ID
    //private val bannerId = bannerIdTest
    private val interstitialId = interstitialIdTest

    private var mInterstitialAd: InterstitialAd? = null
    private var isAdLoading = false

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private val PREFS_NAME = "VscoLoaderPrefs"
    private val KEY_APP_OPEN_COUNT = "AppOpenCount"

    private lateinit var binding: ActivityMainBinding
    private var fetchJob: Job? = null

    private lateinit var requestNotificationLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var requestWritePermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

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

            incrementSuccessfulRuns()

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

    private fun loadInterstitialAd() {
        if (isAdLoading || mInterstitialAd != null) return
        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(this, interstitialId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e("vscoloader_interstitial_fail", "failed to load interstitial ad")
                logEvent("vs_interstitial_fail", "", "Code: ${adError.code} | Message: ${adError.message}")
                mInterstitialAd = null
                isAdLoading = false
            }
            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                Log.i("VscoLoader", "loaded interstital ad")
                mInterstitialAd = interstitialAd
                isAdLoading = false
                mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() { mInterstitialAd = null; loadInterstitialAd() }
                    override fun onAdFailedToShowFullScreenContent(adError: AdError) { mInterstitialAd = null }
                    override fun onAdShowedFullScreenContent() { mInterstitialAd = null }
                }
            }
        })
    }

    private fun logEvent(eventName: String, input_url: String?, more: String?) {
        val bundle = Bundle()
        if (input_url != null) bundle.putString("input_url", input_url)
        if (more != null) bundle.putString("more", more)
        firebaseAnalytics.logEvent(eventName, bundle)
        Log.d("Analytics", "Logged event: $eventName")
    }

    private fun showInterstitial() {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_GOLD", false)) {
            mInterstitialAd?.show(this) ?: loadInterstitialAd()
        }
    }

    private fun incrementSuccessfulRuns() {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)

        // 1. Increment Counter
        val currentCount = prefs.getInt("SUCCESS_RUNS", 0) + 1
        prefs.edit().putInt("SUCCESS_RUNS", currentCount).apply()

        Log.i("MainActivity", "Successful Runs: $currentCount")

        // 2. Check if multiple of 4
        if (currentCount % 4 == 0) {
            // show interstitial ad
            showInterstitial()
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
                            //if (VscoLoader.isShared) {
                            //    startDownloadService() // This triggers updateUI(DOWNLOADING)
                            //} else {
                            //    updateUI(UIState.PREVIEW) // This triggers updateUI(PREVIEW)
                            //}
                            // --- FIX END ---
                            // TODO clean up
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

        eventName?.let {
            firebaseAnalytics.logEvent(it, null)
            Log.d("Analytics", "Logged UI Event: $it")
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

                // You can remove the old "download_finished" call if you want
                // to strictly use "vl_ui_finish" now.
                //incrementSuccessfulRuns()
            }
        }

        binding.root.post {
            binding.root.requestLayout()
            binding.root.invalidate()
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
                            Toast.makeText(this, "Thanks for your support <3", Toast.LENGTH_SHORT).show()
                            saveGoldStatus(true)
                            updateUpgradeIcon(true)
                            binding.adView.visibility = View.GONE
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
            // Load Ads if NOT gold
            initAdMob()
        } else {
            // Hide Ads if Gold
            binding.adView.visibility = View.GONE
            //mInterstitialAd = null
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

        // Ensure the view is visible
        binding.adView.visibility = View.VISIBLE

        // Load the ad defined in XML
        val adRequest = AdRequest.Builder().build()
        binding.adView.loadAd(adRequest)

        // Log for confirmation
        binding.adView.adListener = object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e("AdMobBanner", "XML Load Failed: ${error.message}")
            }
            override fun onAdLoaded() {
                Log.d("AdMobBanner", "XML Ad Loaded")
            }
        }

        // load initial interstitial
        loadInterstitialAd()
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

        // check whether to show in-app review
        if (!VscoLoader.isShared) {
            checkAndShowInAppReview()
        }
    }

    private fun checkAndShowInAppReview() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCount = sharedPrefs.getInt(KEY_APP_OPEN_COUNT, 0) + 1

        // Save the new count immediately
        sharedPrefs.edit().putInt(KEY_APP_OPEN_COUNT, currentCount).apply()

        Log.d("MainActivity", "App Open Count: $currentCount")

        // Trigger only on the 3rd open
        if (currentCount == 3) {
            val manager = ReviewManagerFactory.create(this)
            val request = manager.requestReviewFlow()

            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // We got the ReviewInfo object
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(this, reviewInfo)
                    flow.addOnCompleteListener { _ ->
                        // The flow has finished. The API does not indicate whether the user
                        // reviewed or not, or even if the review dialog was shown.
                        // Thus, no matter the result, we continue our app flow.
                        Log.d("MainActivity", "In-App Review flow completed")
                    }
                } else {
                    // There was some problem, log or handle the error code.
                    Log.e("MainActivity", "Review info request failed", task.exception)
                }
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
                logInputEvent("vl_action_share") // Optional: log that they shared

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
        val bundle = Bundle().apply {
            putString("error_message", error.message ?: "Unknown error")
            putString("error_class", error.javaClass.simpleName)
        }
        firebaseAnalytics.logEvent(eventName, bundle)
        Log.e("Analytics", "Logged Error: $eventName", error)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(finishReceiver)
        unregisterReceiver(progressReceiver)
    }
}