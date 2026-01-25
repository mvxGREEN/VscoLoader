package com.xxxgreen.mvx.downloader4vsco

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object VscoLoader {
    private const val TAG = "VscoLoader"
    private const val BASE_IMAGE_URL = "im.vsco.co/"

    // Global variables from your C# file
    var mThumbnailFilename = ""
    var mM3uUrl = ""

    // State variables
    var mMediaUrls = mutableListOf<String>()
    var mChunkUrls = mutableListOf<String>()
    var mTitle = ""
    var isShared = false
    var isProfile = false
    var isCollection = false
    var mM3uFileName = "video_playlist"
    var mFilePath = ""

    val absPathDocs: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/"

    val absPathDocsTemp: String
        get() = absPathDocs + "temp/"

    fun prepareFileDirs() {
        File(absPathDocs).mkdirs()
        File(absPathDocsTemp).mkdirs()
    }

    fun resetVars() {
        mMediaUrls.clear()
        mChunkUrls.clear()
        mTitle = ""
        isProfile = false
        isCollection = false
        mM3uUrl = ""
        DownloadReceiver.reset()
    }

    fun downloadFile(context: Context, url: String) {
        val fileName = if (url.contains(".mp4")) "$mTitle.mp4" else "$mTitle.jpg"

        // Basic Download Manager Request
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    fun downloadM3u(context: Context, url: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("m3u8 download")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, "$mM3uFileName.m3u8")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    // State variables for the receiver to track
    var mCountChunks = 0
    var mCountChunksFinal = 0

    // HELPER: Download a specific TS chunk
    fun downloadTs(context: Context, url: String, index: Int) {
        val fileName = "s$index.ts"
        // Log.d(TAG, "DownloadTs url=$url index=$index")

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("ts download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN) // Hide chunk downloads
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, "temp/$fileName")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    // HELPER: Read the downloaded .m3u8 file and extract lines
    fun extractUrlsFromM3u(): MutableList<String> {
        val urls = mutableListOf<String>()
        val file = File(absPathDocs + mM3uFileName + ".m3u8")

        try {
            if (file.exists()) {
                file.forEachLine { line ->
                    // Standard M3U parsing: skip comments
                    if (!line.startsWith("#") && line.isNotBlank()) {
                        urls.add(line)
                    }
                }
                // Delete the playlist file after reading
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing m3u", e)
        }
        return urls
    }

    // HELPER: Combine all .ts files into one .mp4
    fun concatTs(): String {
        Log.d(TAG, "ConcatTs started")
        val destPath = getUniqueFilePath(absPathDocs + mTitle + ".mp4")
        mFilePath = destPath // Save for scanning later

        val tempDir = File(absPathDocsTemp)

        // Ensure we grab files s0.ts, s1.ts, s2.ts in correct integer order
        val chunkFiles = tempDir.listFiles { _, name -> name.endsWith(".ts") }
            ?.sortedBy {
                // Extract the number between 's' and '.ts'
                it.name.substringAfter("s").substringBefore(".ts").toIntOrNull() ?: 0
            }

        if (chunkFiles.isNullOrEmpty()) {
            Log.e(TAG, "No chunks found to concat")
            return ""
        }

        try {
            FileOutputStream(destPath).use { output ->
                chunkFiles.forEach { file ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            Log.d(TAG, "Concat finished: $destPath")
        } catch (e: Exception) {
            Log.e(TAG, "Concat error", e)
        }
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
        File(absPathDocsTemp).deleteRecursively()
    }

    /**
     * Ported from C#: ExtractThumbnailUrl
     * Extracts the thumbnail or poster URL and sets the mThumbnailFilename
     */
    fun extractThumbnail(url: String, html: String): String {
        var tu = ""

        // 1. Check for video thumbnail (mux)
        if (html.contains("https://image.mux.com")) {
            Log.d(TAG, "found video thumbnail url")
            val startIndex = html.indexOf("https://image.mux.com/")
            if (startIndex != -1) {
                tu = html.substring(startIndex)
                // Extract until the next quote
                val quoteIndex = tu.indexOf('"')
                if (quoteIndex != -1) {
                    tu = tu.substring(0, quoteIndex)
                }
            }
        }
        // 2. Check for poster url
        else if (html.contains("https://vsco.co/api/1.0/videos/mux/")) {
            Log.d(TAG, "found poster thumbnail url")
            val startIndex = html.indexOf("https://vsco.co/api/1.0/videos/mux/")
            if (startIndex != -1) {
                var pu = html.substring(startIndex)
                val quoteIndex = pu.indexOf('"')
                if (quoteIndex != -1) {
                    pu = pu.substring(0, quoteIndex)
                }

                // remove width parameter if exists
                if (pu.contains("?")) {
                    pu = pu.substringBefore("?")
                }

                // re-add width parameter
                tu = "$pu?w=1200"
            }
        }
        // 3. Check for standard image url
        else if (html.contains(BASE_IMAGE_URL)) {
            Log.d(TAG, "found normal thumbnail url")
            val s = html.lastIndexOf(BASE_IMAGE_URL)
            if (s != -1) {
                val endSearch = html.indexOf('"', s)
                if (endSearch != -1) {
                    val l = endSearch - s
                    tu = "https://" + html.substring(s, s + l)

                    if (tu.endsWith("/") || tu.endsWith("\\")) {
                        tu = tu.substring(0, tu.length - 1)
                    }

                    // remove size parameters
                    if (tu.contains("?")) {
                        tu = tu.substringBefore("?")
                    }

                    // set thumbnail filename based on extension
                    mThumbnailFilename = if (tu.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                    Log.d(TAG, "MThumbnailFilename=$mThumbnailFilename")
                }
            }
        } else {
            Log.d(TAG, "missing thumbnail and poster url!")
        }

        return tu
    }

    /**
     * Ported from C#: ExtractDownloadUrls
     * Returns a list of media URLs.
     * Marked as 'suspend' because the video logic requires a network call.
     */
    suspend fun extractDownloadUrls(url: String, html: String): List<String> {
        Log.d(TAG, "ExtractDownloadUrl")
        val dlus = mutableListOf<String>()

        // 1. Check for mp4 url
        if (html.contains("https://img.vsco.co/")) {
            Log.d(TAG, "found mp4 url!")
            val startIndex = html.indexOf("https://img.vsco.co/")
            if (startIndex != -1) {
                var dlu = html.substring(startIndex)
                val quoteIndex = dlu.indexOf('"')
                if (quoteIndex != -1) {
                    dlu = dlu.substring(0, quoteIndex)
                    Log.d(TAG, "found MP4 url dlu=$dlu")
                    dlus.add(dlu)
                }
            }
        }
        // 2. Check for video url
        else if (html.contains("/video/")) {
            Log.d(TAG, "found video url!")

            // find og:url
            val ogIndex = html.indexOf("og:url")
            if (ogIndex != -1) {
                var vurl = html.substring(ogIndex)
                val contentIndex = vurl.indexOf("content=")
                if (contentIndex != -1) {
                    vurl = vurl.substring(contentIndex + 9) // +9 to skip 'content="'
                    val quoteIndex = vurl.indexOf('"')
                    if (quoteIndex != -1) {
                        vurl = vurl.substring(0, quoteIndex)

                        // Load video url response (Network Call)
                        val vhtml = loadResponse(vurl)

                        if (vhtml.contains("stream.mux.com")) {
                            Log.d(TAG, "found m3u8 url")
                            val streamIndex = vhtml.indexOf("stream.mux.com")
                            if (streamIndex != -1) {
                                // backtrack to find https://
                                // C# logic: "https://" + vhtml[vhtml.IndexOf("stream.mux.com")..];
                                // Note: The C# logic assumes the string starts with stream.mux.com,
                                // but we need to ensure the protocol is attached correctly.

                                var dlu = "https://" + vhtml.substring(streamIndex)
                                val endQuote = dlu.indexOf('"')
                                if (endQuote != -1) {
                                    dlu = dlu.substring(0, endQuote)

                                    // decode m3u8 url (C#: dlu.Replace("\\u002F", "/"))
                                    dlu = dlu.replace("\\u002F", "/")

                                    mM3uUrl = dlu
                                    dlus.add(dlu)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 3. Standard Image
        else if (html.contains(BASE_IMAGE_URL)) {
            Log.d(TAG, "found normal url!")
            val s = html.lastIndexOf(BASE_IMAGE_URL)
            if (s != -1) {
                val endSearch = html.indexOf('"', s)
                if (endSearch != -1) {
                    val l = endSearch - s
                    var dlu = "https://" + html.substring(s, s + l)

                    if (dlu.endsWith("/") || dlu.endsWith("\\")) {
                        dlu = dlu.substring(0, dlu.length - 1)
                    }

                    // remove size parameters
                    if (dlu.contains("?")) {
                        dlu = dlu.substringBefore("?")
                    }

                    dlus.add(dlu)
                }
            }
        }

        return dlus
    }

    /**
     * Ported from C#: LoadResponse
     * Helper to perform a GET request and return the body as a string.
     * Runs on IO thread.
     */
    private suspend fun loadResponse(urlString: String): String {
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "LoadResponse url=$urlString")
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val stream = connection.inputStream
                val reader = BufferedReader(InputStreamReader(stream))

                var line: String? = reader.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = reader.readLine()
                }

                reader.close()
                stream.close()
            } catch (e: Exception) {
                Log.e(TAG, "connection failed or null pointer", e)
                return@withContext ""
            } finally {
                connection?.disconnect()
            }
            return@withContext sb.toString()
        }
    }
}
