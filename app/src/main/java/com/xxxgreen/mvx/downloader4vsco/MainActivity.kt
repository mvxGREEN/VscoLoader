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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.billingclient.api.*
import com.bumptech.glide.Glide
import com.google.android.gms.ads.*
// IMPORT THE GENERATED BINDING CLASSES
import com.xxxgreen.mvx.downloader4vsco.databinding.ActivityMainBinding
import com.xxxgreen.mvx.downloader4vsco.databinding.DialogUpgradeBinding
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.regex.Pattern
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    private val bannerIdTest = "ca-app-pub-3940256099942544/6300978111" // Test ID
    private val bannerIdReal = "ca-app-pub-7417392682402637/1939309490" // Real ID
    private val bannerId = bannerIdTest

    // 1. Declare Binding Object
    private lateinit var binding: ActivityMainBinding

    private var fetchJob: Job? = null

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
                // Update Overlay UI
                val progressBar = findViewById<ProgressBar>(R.id.pbOverlay)
                // (Note: Ensure your XML ID matches, see step 5)

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

            // Check if this session was initiated via Share
            if (VscoLoader.isShared) {
                Toast.makeText(context, "Saved!", Toast.LENGTH_LONG).show()

                // Delay slightly to let the user see the success message, then close
                Handler(Looper.getMainLooper()).postDelayed({
                    finishAndRemoveTask() // Closes the app and removes from recents (optional) or just finish()
                }, 1000)
            } else {
                Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. Inflate Layout via Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Utilities
        setupListeners()
        VscoLoader.prepareFileDirs()

        // Billing & Ads
        setupBilling()
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        val isGold = prefs.getBoolean("IS_GOLD", false)
        checkSubscriptionAndLoadAds(isGold)

        // Setup Toolbar
        updateUpgradeIcon(isGold)
        setupToolbarMenu()

        checkPermissions()

        setupWebView()

        // Register Receiver
        val filter = IntentFilter("DOWNLOAD_FINISHED_ACTION")
        ContextCompat.registerReceiver(this, finishReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED)

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

        // Paste Button
        binding.btnPaste.setOnTouchListener { v, event ->
            when (event.action) {
                // When pressed: Turn Gold
                android.view.MotionEvent.ACTION_DOWN -> {
                    (v as? android.widget.ImageView)?.setColorFilter(Color.parseColor("#FFD700"))
                }
                // When released or cancelled: Revert to original
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    (v as? android.widget.ImageView)?.clearColorFilter()
                }
            }
            false // Return false so the standard OnClickListener still fires!
        }

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

        // Clear Button
        binding.btnClear.setOnClickListener {
            // 1. Cancel background work
            VscoLoader.cancelBatch(this)

            if (currentState == UIState.DOWNLOADING)
                Toast.makeText(this, "Download Cancelled", Toast.LENGTH_SHORT).show()

            // 2. Clear Input
            binding.etMainInput.setText("")

            // 3. Reset UI to Empty State
            updateUI(UIState.EMPTY)
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
    }

    private fun updateBackgroundMenuVisibility() {
        val item = binding.toolbar.menu.findItem(R.id.action_enable_background) ?: return
        // Show item ONLY if permissions are missing
        item.isVisible = !hasBackgroundPermissions()
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
                    requestBackgroundPermissions()
                    true
                }
                else -> false
            }
        }
    }

    // --- UPGRADE DIALOG (WITH BINDING) ---

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
        updateUI(UIState.LOADING)
        binding.webView.loadUrl(url)
        // logic continues in setupWebView()
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
                // WebViews often fire onPageFinished multiple times for redirects.
                // We ignore if it's the exact same URL we just processed.
                if (url != null && url != lastLoadedUrl) {
                    lastLoadedUrl = url

                    val username = VscoLoader.extractUsernameFromUrl(url)
                    if (username.isNotEmpty() && username != "api") {
                        VscoLoader.mTitle = username
                    }

                    if (url.contains("/media/") || url.contains("/video/")) {
                        Log.d("MainActivity", "Shortlink resolved to Media: $url")
                        loadMediaData(url)
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
                                    binding.tvTitle.text = "Found $count Items..."

                                    // SHOW UI (Without Button)
                                    if (binding.previewCard.visibility != View.VISIBLE) {
                                        binding.previewCard.fadeIn()
                                        binding.bottomControlCard.fadeIn()

                                        // CRITICAL FIX: Explicitly hide the button while loading
                                        binding.btnAction.visibility = View.INVISIBLE

                                        binding.layoutLoading.fadeOut()
                                    }

                                    if (count > 0 && isValidContextForGlide(this@MainActivity)) {
                                        Glide.with(this@MainActivity)
                                            .load(VscoLoader.mMediaUrls[0])
                                            .centerCrop()
                                            .into(binding.ivPreview)
                                    }
                                }
                            }

                            if (isActive) {
                                withContext(Dispatchers.Main) {
                                    if (VscoLoader.mMediaUrls.isNotEmpty()) {
                                        binding.tvTitle.text = "${VscoLoader.mMediaUrls.size} Items Found"
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

        updateUI(UIState.LOADING)

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
                            binding.tvTitle.text = VscoLoader.mTitle

                            if (isValidContextForGlide(this@MainActivity)) {
                                Glide.with(this@MainActivity)
                                    .load(thumbUrl)
                                    .centerCrop()
                                    .into(binding.ivPreview)
                            }
                            updateUI(UIState.PREVIEW)

                            if (VscoLoader.isShared) {
                                startDownloadService()
                            }
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
        Log.d("MainActivity", "Updating UI to $state")

        currentState = state

        // 1. DEFAULT: Enable inputs (Reset to normal for EMPTY, PREVIEW, FINISHED)
        binding.etMainInput.isEnabled = true
        binding.btnPaste.isEnabled = true
        binding.btnPaste.alpha = 1.0f

        when (state) {
            UIState.EMPTY -> {
                binding.layoutLoading.fadeOut()
                binding.previewCard.fadeOut()
                binding.bottomControlCard.fadeOut()
            }
            UIState.LOADING -> {
                binding.layoutLoading.fadeIn()
                binding.previewCard.fadeOut()
                binding.bottomControlCard.fadeOut() // Optional: keep hidden or show
            }
            UIState.PREVIEW -> {
                binding.layoutLoading.fadeOut()
                binding.previewCard.fadeIn()
                binding.bottomControlCard.fadeIn()
                binding.overlayDownloading.fadeOut()

                binding.btnAction.setImageResource(R.drawable.ic_download)
                binding.btnAction.isEnabled = true
                binding.btnAction.fadeIn()
            }
            UIState.DOWNLOADING -> {
                binding.layoutLoading.fadeOut()
                binding.previewCard.fadeIn()
                binding.bottomControlCard.fadeIn()
                binding.overlayDownloading.fadeIn()
                binding.btnAction.fadeOut(targetVisibility = View.INVISIBLE)

                // --- NEW: LOCK INPUTS ---
                // We disable the text box and paste button so the user can't interfere.
                // The Clear button (btnClear) remains enabled so they can Cancel.
                binding.etMainInput.isEnabled = false

                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.3f // Dim it so it looks disabled
            }
            UIState.FINISHED -> {
                binding.layoutLoading.fadeOut()
                binding.previewCard.fadeIn()
                binding.bottomControlCard.fadeIn()
                binding.overlayDownloading.fadeOut()

                binding.btnAction.setImageResource(R.drawable.ic_check)
                binding.btnAction.isEnabled = false
                binding.btnAction.fadeIn()
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

    // check permissions - but dont request
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                //requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
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