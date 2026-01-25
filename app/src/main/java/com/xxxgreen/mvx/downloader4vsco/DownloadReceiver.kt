package com.xxxgreen.mvx.downloader4vsco

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadReceiver : BroadcastReceiver() {
    private val TAG = "DownloadReceiver"

    companion object {
        var mCountChunks = 0
        var mCountChunksFinal = 0

        fun reset() {
            mCountChunks = 0
            mCountChunksFinal = 0
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
            Log.d(TAG, "Download Complete Received")

            // Case 1: Playlist just finished downloading
            if (VscoLoader.mCountChunksFinal == 0 && VscoLoader.mM3uUrl.isNotEmpty()) {
                handlePlaylistDownload(context)
            }
            // Case 2: A video chunk finished downloading
            else if (VscoLoader.mCountChunksFinal > 0 && VscoLoader.mCountChunks < VscoLoader.mCountChunksFinal) {
                handleChunkDownload(context)
            }
            // Case 3 (MISSING): Standard File (Image/Video) finished
            else {
                handleStandardDownload(context)
            }
        }
    }

    private fun handlePlaylistDownload(context: Context) {
        Log.d(TAG, "m3u8 file downloaded, processing...")

        // Go to background thread for file I/O
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Extract URLs
            VscoLoader.mChunkUrls = VscoLoader.extractUrlsFromM3u()

            // 2. Filter valid URLs
            VscoLoader.mChunkUrls.removeAll { !it.contains("https://") }

            // 3. Check for "Master" playlist (nested m3u8)
            if (VscoLoader.mChunkUrls.isNotEmpty() && VscoLoader.mChunkUrls[0].contains("rendition.m3u8")) {
                Log.d(TAG, "Found master playlist, downloading rendition...")

                // Based on C# logic: MM3uUrl = MChunkUrls[2]; mM3uFileName = "rendition";
                // CAUTION: Index [2] is risky, but we are porting your logic faithfully.
                if (VscoLoader.mChunkUrls.size > 2) {
                    VscoLoader.mM3uUrl = VscoLoader.mChunkUrls[2]
                    VscoLoader.mM3uFileName = "rendition"

                    // Download the inner playlist and RETURN (wait for next onReceive)
                    VscoLoader.downloadM3u(context, VscoLoader.mChunkUrls[1])
                }
                return@launch
            }

            // 4. If we are here, we have actual video chunks
            VscoLoader.mCountChunksFinal = VscoLoader.mChunkUrls.size
            Log.d(TAG, "Final Chunks Count: ${VscoLoader.mCountChunksFinal}")

            // 5. Download all chunks
            // Note: DownloadManager handles parallelization automatically
            for ((index, url) in VscoLoader.mChunkUrls.withIndex()) {
                // Small delay to prevent flooding the DownloadManager (optional, inherited from C# logic)
                // Thread.sleep(50)
                VscoLoader.downloadTs(context, url, index)
            }
        }
    }

    private fun handleChunkDownload(context: Context) {
        VscoLoader.mCountChunks++
        Log.d(TAG, "Chunk ${VscoLoader.mCountChunks}/${VscoLoader.mCountChunksFinal} downloaded")

        // Update Progress (Optional: Send Broadcast to UI to update ProgressBar)
        // val progress = (VscoLoader.mCountChunks.toFloat() / VscoLoader.mCountChunksFinal.toFloat()) * 100
        // sendProgressBroadcast(context, progress.toInt())

        // Check if finished
        if (VscoLoader.mCountChunks >= VscoLoader.mCountChunksFinal) {
            Log.d(TAG, "All chunks downloaded. Concatenating...")

            CoroutineScope(Dispatchers.IO).launch {
                // 1. Concat
                VscoLoader.concatTs()

                // 2. Scan media so it shows in Gallery
                scanMediaFile(context, VscoLoader.mFilePath)

                // 3. Cleanup
                VscoLoader.deleteTempFiles()

                // 4. Finish
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Sending FINISHED broadcast")
                    val finishIntent = Intent("DOWNLOAD_FINISHED_ACTION")
                    finishIntent.setPackage(context.packageName) // Security fix for API < 33
                    context.sendBroadcast(finishIntent)

                    // Reset Logic (Prepare for next file if multiple)
                    // VscoLoader.resetVars()
                }
            }
        }
    }

    private fun handleStandardDownload(context: Context) {
        Log.d(TAG, "Standard media file downloaded.")

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Scan the file so it appears in Gallery immediately
            // Note: For standard downloads, DownloadManager usually handles scanning,
            // but we do it manually to be safe or if we renamed it.
            val filePath = VscoLoader.absPathDocs + VscoLoader.mTitle +
                    (if (VscoLoader.mThumbnailFilename.contains("jpg")) ".jpg" else ".mp4")
            scanMediaFile(context, filePath)

            // 2. Check if there are more items to download (Gallery/Collection support)
            if (VscoLoader.mMediaUrls.isNotEmpty()) {
                Log.d(TAG, "Downloading next item in queue...")
                val nextUrl = VscoLoader.mMediaUrls.removeAt(0)

                withContext(Dispatchers.Main) {
                    VscoLoader.downloadFile(context, nextUrl)
                }
            } else {
                // 3. Queue empty? All done.
                Log.d(TAG, "Queue empty. Sending FINISHED broadcast.")
                withContext(Dispatchers.Main) {
                    val finishIntent = Intent("DOWNLOAD_FINISHED_ACTION")
                    finishIntent.setPackage(context.packageName) // Security fix
                    context.sendBroadcast(finishIntent)
                }
            }
        }
    }

    private fun scanMediaFile(context: Context, path: String) {
        try {
            val file = File(path)
            val uri = android.net.Uri.fromFile(file)
            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
            context.sendBroadcast(scanIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning file", e)
        }
    }
}