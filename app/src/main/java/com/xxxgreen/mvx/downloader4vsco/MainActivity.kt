package com.xxxgreen.mvx.downloader4vsco

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
            Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. Inflate Layout via Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Utilities
        setupListeners() // Moved listeners here since views are already bound
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
        ContextCompat.registerReceiver(this, finishReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val progressFilter = IntentFilter(DownloadService.PROGRESS_UPDATE_ACTION)
        ContextCompat.registerReceiver(this, progressReceiver, progressFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Initial State
        updateUI(UIState.EMPTY)
        handleSharedIntent()
    }

    // --- VIEW BINDING SETUP ---

    private fun setupListeners() {
        // Paste Button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                binding.etMainInput.setText(text)
                handleInput(text)
            }
        }

        // Clear Button
        binding.btnClear.setOnClickListener {
            binding.etMainInput.setText("")
            VscoLoader.resetVars()
            updateUI(UIState.EMPTY)
        }

        // Action Button
        binding.btnAction.setOnClickListener {
            if (currentState == UIState.PREVIEW) {
                startDownloadService()
            }
        }

        // Text Watcher
        binding.etMainInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
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

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_upgrade -> {
                    showUpgradeDialog()
                    true
                }
                /*
                R.id.action_rate -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    startActivity(intent)
                    true
                }
                 */
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
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnNah.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnUpgrade.setOnClickListener {
            dialog.dismiss()
            launchBillingFlow()
        }

        dialog.show()
    }

    // --- LOGIC & UI UPDATES ---

    private fun handleInput(rawInput: String) {
        // 1. CANCEL ANY RUNNING FETCH (Profile OR Single Post)
        fetchJob?.cancel()

        var input = rawInput.trim()
        if (input.contains("http://")) input = input.replace("http://", "https://")
        if (input.endsWith("/")) input = input.substring(0, input.length - 1)

        if (!VALID_INPUT_REGEX.matcher(input).find()) return

        var url = "https://"
        if (input.contains("vs.co")) url += input.substring(input.indexOf("vs.co"))
        else if (input.contains("vsco.co")) url += input.substring(input.indexOf("vsco.co"))
        else url = input

        // Note: This resets the list. If a download is currently RUNNING,
        // this will stop it from downloading further items.
        VscoLoader.resetVars()

        if (url.contains("/collection")) {
            VscoLoader.isCollection = true
            val username = VscoLoader.extractUsernameFromUrl(url)
            if (username.isNotEmpty()) VscoLoader.mTitle = username
            url += "/1"
            loadInWebView(url)
        }
        else if (!url.contains("/media") && !url.contains("/video") && !url.contains("vs.co")) {
            VscoLoader.isProfile = true
            val username = VscoLoader.extractUsernameFromUrl(url)
            if (username.isNotEmpty()) VscoLoader.mTitle = username
            url += "/gallery"
            loadInWebView(url)
        }
        else if (input.contains("vs.co")) {
            loadInWebView(url)
        }
        else {
            loadMediaData(url)
        }
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
                if (url != null) {
                    val username = VscoLoader.extractUsernameFromUrl(url)
                    if (username.isNotEmpty() && username != "api") {
                        VscoLoader.mTitle = username
                    }

                    // FIX: Detect if Shortlink resolved to a Single Media page
                    // If so, stop waiting for Profile API and switch to Scraper logic
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

                    val headers = request?.requestHeaders ?: emptyMap()
                    val cookie = CookieManager.getInstance().getCookie(url) ?: ""

                    fetchJob = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            VscoLoader.processProfile(url, cookie, headers)

                            if (isActive) {
                                withContext(Dispatchers.Main) {
                                    if (VscoLoader.mMediaUrls.isNotEmpty()) {
                                        binding.tvTitle.text = "${VscoLoader.mMediaUrls.size} Items Found"

                                        if (isValidContextForGlide(this@MainActivity)) {
                                            Glide.with(this@MainActivity)
                                                .load(VscoLoader.mMediaUrls[0])
                                                .centerCrop()
                                                .into(binding.ivPreview)
                                        }
                                        updateUI(UIState.PREVIEW)
                                    } else {
                                        Toast.makeText(this@MainActivity, "No items found in profile", Toast.LENGTH_SHORT).show()
                                        updateUI(UIState.EMPTY)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Profile fetch failed", e)
                            if (isActive) {
                                withContext(Dispatchers.Main) {
                                    updateUI(UIState.EMPTY)
                                }
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
        currentState = state
        when (state) {
            UIState.EMPTY -> {
                binding.layoutLoading.visibility = View.GONE
                binding.previewCard.visibility = View.GONE
                binding.bottomControlCard.visibility = View.GONE
            }
            UIState.LOADING -> {
                binding.layoutLoading.visibility = View.VISIBLE
                binding.previewCard.visibility = View.GONE
                binding.bottomControlCard.visibility = View.GONE
            }
            UIState.PREVIEW -> {
                binding.layoutLoading.visibility = View.GONE
                binding.previewCard.visibility = View.VISIBLE
                binding.overlayDownloading.visibility = View.GONE
                binding.bottomControlCard.visibility = View.VISIBLE

                binding.btnAction.visibility = View.VISIBLE
                binding.btnAction.setImageResource(R.drawable.ic_download)
                binding.btnAction.isEnabled = true
            }
            UIState.DOWNLOADING -> {
                binding.layoutLoading.visibility = View.GONE
                binding.previewCard.visibility = View.VISIBLE
                binding.overlayDownloading.visibility = View.VISIBLE
                binding.bottomControlCard.visibility = View.VISIBLE

                binding.btnAction.visibility = View.INVISIBLE
            }
            UIState.FINISHED -> {
                binding.layoutLoading.visibility = View.GONE
                binding.previewCard.visibility = View.VISIBLE
                binding.overlayDownloading.visibility = View.GONE
                binding.bottomControlCard.visibility = View.VISIBLE

                binding.btnAction.visibility = View.VISIBLE
                binding.btnAction.setImageResource(R.drawable.ic_check)
                binding.btnAction.isEnabled = false
            }
        }
    }

    // --- BILLING LOGIC ---

    private fun setupBilling() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
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
        ) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                productDetails = productDetailsList[0]
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
        adView.adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
        binding.adContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                VscoLoader.isShared = true
                binding.etMainInput.setText(text)
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
        unregisterReceiver(progressReceiver)
    }
}