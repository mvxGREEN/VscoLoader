package com.xxxgreen.mvx.downloader4vsco

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object VscoLoader {
    private const val TAG = "VscoLoader"
    private const val BASE_IMAGE_URL = "im.vsco.co/"

    // Global variables
    var mThumbnailFilename = ""
    var mM3uUrl = ""
    var mM3uFileName = "video_playlist"
    var mFilePath = ""

    // State variables
    var mMediaUrls = mutableListOf<String>()
    var mChunkUrls = mutableListOf<String>()
    var mTitle = ""
    var isShared = false
    var isProfile = false
    var isCollection = false

    // Receiver State
    var mCountChunks = 0
    var mCountChunksFinal = 0

    var totalItems = 0
    var completedItems = 0

    // Change these properties in VscoLoader.kt
    val absPathPictures: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/VSLoader/"

    val absPathMovies: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath + "/VSLoader/"

    fun prepareFileDirs() {
        File(absPathPictures).mkdirs()
        File(absPathMovies).mkdirs()
        File(absPathMovies + "temp/").mkdirs() // Keep temp files in Movies/temp
    }

    // New Kill Switch Flag
    var isCancelled = false

    fun resetVars() {
        // When we start fresh, ensure we are NOT cancelled
        isCancelled = false

        mMediaUrls.clear()
        mChunkUrls.clear()
        mTitle = ""
        isProfile = false
        isCollection = false
        mM3uUrl = ""

        // Reset counters
        totalItems = 0
        completedItems = 0
        DownloadReceiver.reset()
    }

    // --- CANCEL DOWNLOAD ---
    fun cancelBatch(context: Context) {
        // 1. Flip the Kill Switch
        // This tells DownloadReceiver to IGNORE any downloads that finish after this point.
        isCancelled = true

        // 2. Clear the Queue
        mMediaUrls.clear()
        mChunkUrls.clear()

        // 3. Stop the Service (Hides Notification)
        val intent = Intent(context, DownloadService::class.java)
        intent.action = "STOP_SERVICE"
        context.startService(intent)

        // 4. Clean up any partial video chunks
        deleteTempFiles()
    }

    // --- DOWNLOADER FUNCTIONS ---

    fun downloadFile(context: Context, url: String) {
        if (url.contains(".m3u8") || url.contains("stream.mux.com")) {
            downloadM3u(context, url)
            return
        }

        val isVideo = url.contains(".mp4")
        val fileName = if (isVideo) "$mTitle.mp4" else "$mTitle.jpg"

        // Choose Directory based on type
        val subDir = "VSLoader"
        val publicDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        val visibility = if (isProfile || isCollection) {
            DownloadManager.Request.VISIBILITY_VISIBLE
        } else {
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setNotificationVisibility(visibility)
            .setDestinationInExternalPublicDir(publicDir, "$subDir/$fileName") // Updated Path
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    fun downloadM3u(context: Context, url: String) {
        mM3uUrl = url // Flag for receiver
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("m3u8 download")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "VSLoader/$mM3uFileName.m3u8")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    fun downloadTs(context: Context, url: String, index: Int) {
        val fileName = "s$index.ts"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("ts download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "VSLoader/$fileName")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    // --- FILE OPERATIONS ---

    fun extractUrlsFromM3u(): MutableList<String> {
        val urls = mutableListOf<String>()
        val file = File(absPathMovies + mM3uFileName + ".m3u8")
        try {
            if (file.exists()) {
                file.forEachLine { line ->
                    if (!line.startsWith("#") && line.isNotBlank()) {
                        urls.add(line)
                    }
                }
                file.delete()
            }
        } catch (e: Exception) { Log.e(TAG, "Error parsing m3u", e) }
        return urls
    }

    fun concatTs(): String {
        Log.d(TAG, "ConcatTs started")
        val destPath = getUniqueFilePath(absPathMovies + mTitle + ".mp4")
        mFilePath = destPath

        val tempDir = File(absPathMovies)
        val chunkFiles = tempDir.listFiles { _, name -> name.endsWith(".ts") }
            ?.sortedBy { it.name.substringAfter("s").substringBefore(".ts").toIntOrNull() ?: 0 }

        if (chunkFiles.isNullOrEmpty()) return ""

        try {
            FileOutputStream(destPath).use { output ->
                chunkFiles.forEach { file ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Concat error", e) }
        return destPath
    }

    private fun getUniqueFilePath(path: String): String {
        var finalPath = path
        var count = 1
        while (File(finalPath).exists()) {
            val ext = path.substringAfterLast(".")
            val name = path.substringBeforeLast(".")
            finalPath = "$name$count.$ext"
            count++
        }
        return finalPath
    }

    fun deleteTempFiles() {
        File(absPathMovies).deleteRecursively()
    }

    // --- EXTRACTION LOGIC (VIDEO / PHOTO) ---

    fun extractThumbnail(url: String, html: String): String {
        var tu = ""
        // Video/Poster logic
        if (html.contains("https://image.mux.com")) {
            val start = html.indexOf("https://image.mux.com/")
            tu = html.substring(start).substringBefore('"')
        } else if (html.contains("/videos/mux/")) {
            val start = html.indexOf("https://vsco.co/api/1.0/videos/mux/")
            if (start != -1) {
                tu = html.substring(start).substringBefore('"').substringBefore("?") + "?w=1200"
            }
        } else if (html.contains(BASE_IMAGE_URL)) {
            val s = html.lastIndexOf(BASE_IMAGE_URL)
            if (s != -1) {
                val endSearch = html.indexOf('"', s)
                if (endSearch != -1) {
                    tu = "https://" + html.substring(s, endSearch)
                    if (tu.contains("?")) tu = tu.substringBefore("?")
                    mThumbnailFilename = if (tu.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                }
            }
        }
        return tu
    }

    suspend fun extractDownloadUrls(url: String, html: String): List<String> {
        Log.d(TAG, "ExtractDownloadUrl")
        val dlus = mutableListOf<String>()

        // 1. MP4 (Direct)
        if (html.contains("https://img.vsco.co/")) {
            val start = html.indexOf("https://img.vsco.co/")
            val dlu = html.substring(start).substringBefore('"')
            dlus.add(dlu)
        }
        // 2. Video (M3U8 Stream)
        else if (html.contains("/video/") || html.contains("og:video") || url.contains("/video/")) {
            // ... (keep existing video logic) ...
            var vurl = ""
            if (html.contains("og:video")) {
                val ogStart = html.indexOf("og:video")
                vurl = html.substring(ogStart).substringAfter("content=\"").substringBefore('"')
            } else if (html.contains("og:url")) {
                val ogStart = html.indexOf("og:url")
                vurl = html.substring(ogStart).substringAfter("content=\"").substringBefore('"')
            }

            if (vurl.isNotEmpty()) {
                val vhtml = loadResponse(vurl)
                if (vhtml.contains("stream.mux.com")) {
                    val streamStart = vhtml.indexOf("stream.mux.com")
                    var dlu = "https://" + vhtml.substring(streamStart).substringBefore('"')
                    dlu = dlu.replace("\\u002F", "/")
                    mM3uUrl = dlu
                    dlus.add(dlu)
                }
            }
        }
        // 3. Standard Image (Strategy A: Raw URL Scan)
        else if (html.contains(BASE_IMAGE_URL)) {
            val s = html.lastIndexOf(BASE_IMAGE_URL)
            if (s != -1) {
                val endSearch = html.indexOf('"', s)
                if (endSearch != -1) {
                    var dlu = "https://" + html.substring(s, endSearch)
                    if (dlu.contains("?")) dlu = dlu.substringBefore("?")
                    dlus.add(dlu)
                }
            }
        }
        // 4. Standard Image (Strategy B: Fallback to og:image)
        else if (html.contains("og:image")) {
            Log.d(TAG, "Using og:image fallback")
            val start = html.indexOf("og:image")
            var dlu = html.substring(start).substringAfter("content=\"").substringBefore('"')
            if (dlu.isNotEmpty()) {
                if (dlu.contains("?")) dlu = dlu.substringBefore("?")
                dlus.add(dlu)
            }
        }

        return dlus
    }

    // --- PROFILE / COLLECTION API LOGIC ---

    // --- NEW HELPER ---
    fun extractUsernameFromUrl(url: String): String {
        return try {
            if (url.contains("vsco.co/") && !url.contains("/api/")) {
                val segment = url.substringAfter("vsco.co/")
                if (segment.contains("/")) {
                    segment.substringBefore("/")
                } else {
                    segment
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    // --- UPDATED PROFILE LOGIC ---
    suspend fun processProfile(
        initialUrl: String,
        cookie: String,
        headers: Map<String, String>,
        onProgress: (Int) -> Unit // <--- NEW CALLBACK
    ) {
        Log.d(TAG, "Processing Profile: $initialUrl")

        var nextUrl = initialUrl
        if (nextUrl.contains("limit=")) {
            nextUrl = nextUrl.substringBefore("limit=") + "limit=14&cursor="
        } else {
            nextUrl += "&limit=14&cursor="
        }

        val visitedCursors = mutableSetOf<String>()
        recursiveFetch(nextUrl, cookie, headers, visitedCursors, 0, onProgress)
    }

    private suspend fun recursiveFetch(
        url: String,
        cookie: String,
        headers: Map<String, String>,
        visitedCursors: MutableSet<String>,
        consecutiveEmptyPages: Int,
        onProgress: (Int) -> Unit // <--- NEW CALLBACK
    ) {
        // Safety Checks
        if (visitedCursors.size > 200) return
        if (consecutiveEmptyPages >= 3) return

        Log.d(TAG, "Fetching Profile JSON: $url")

        // 1. Fetch
        val jsonStr = loadResponseWithHeaders(url, cookie, headers)

        try {
            var itemsAddedThisPage = 0

            // 2. Parse (Using your existing String parsing logic)
            var currentJson = jsonStr
            while (currentJson.contains("responsive_url")) {
                val urlStart = currentJson.indexOf("responsive_url") + 16
                var dlu = currentJson.substring(urlStart).substringAfter('"').substringBefore('"')
                dlu = "https://$dlu"

                if (!mMediaUrls.contains(dlu)) {
                    mMediaUrls.add(dlu)
                    itemsAddedThisPage++

                    if (mMediaUrls.size == 1) {
                        mThumbnailFilename = if (dlu.contains(".mp4")) "video" else "image"
                    }
                }
                currentJson = currentJson.substring(urlStart + 10)
            }

            Log.d(TAG, "Items found this page: $itemsAddedThisPage. Total: ${mMediaUrls.size}")

            // 3. TRIGGER CALLBACK (Report new count to UI)
            if (itemsAddedThisPage > 0) {
                onProgress(mMediaUrls.size)
            }

            // 4. Recursion Logic
            val nextEmptyCount = if (itemsAddedThisPage == 0) consecutiveEmptyPages + 1 else 0

            if (jsonStr.contains("next_cursor")) {
                val cursorStart = jsonStr.indexOf("next_cursor")
                val cursorVal = jsonStr.substring(cursorStart).substringAfter('"').substringAfter('"').substringBefore('"')

                if (cursorVal.isNotEmpty() && cursorVal != "null" && !visitedCursors.contains(cursorVal)) {
                    visitedCursors.add(cursorVal)

                    // CRITICAL: Encode cursor to fix "28 items" bug
                    val encodedCursor = URLEncoder.encode(cursorVal, "UTF-8")

                    val baseUrl = url.substringBefore("&cursor=")
                    val newUrl = "$baseUrl&cursor=$encodedCursor"

                    recursiveFetch(newUrl, cookie, headers, visitedCursors, nextEmptyCount, onProgress)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Profile parse error", e)
        }
    }

    // --- NETWORK HELPERS ---

    private suspend fun loadResponse(urlString: String): String {
        return loadResponseWithHeaders(urlString, "", emptyMap())
    }

    private suspend fun loadResponseWithHeaders(urlString: String, cookie: String, headers: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection

                // Add Headers needed for API
                connection.setRequestProperty("Cookie", cookie)
                headers.forEach { (k, v) ->
                    if(k != "Cookie") connection.setRequestProperty(k, v)
                }
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                connection.connect()
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                var line: String? = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                reader.close()
            } catch (e: Exception) {
                Log.e(TAG, "Network error: $urlString", e)
            } finally {
                connection?.disconnect()
            }
            return@withContext sb.toString()
        }
    }
}