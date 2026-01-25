package com.xxxgreen.mvx.downloader4vsco

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadReceiver : BroadcastReceiver() {
    companion object {
        var mCountChunks = 0
        var mCountChunksFinal = 0

        fun reset() {
            mCountChunks = 0
            mCountChunksFinal = 0
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            Log.d("DownloadReceiver", "Download Complete")
            // Logic to check if it was an m3u8 or a chunk or a standard file
            // Trigger next download or finish

            // Simplified for brevity:
            if (VscoLoader.mM3uUrl.isNotEmpty() && mCountChunksFinal == 0) {
                // Handle m3u8 processing logic here (parse lines, download chunks)
                // This requires migrating the complex logic from the C# Receiver
                processM3u8(context)
            } else {
                // Standard file finished
                context.sendBroadcast(Intent("DOWNLOAD_FINISHED_ACTION"))
            }
        }
    }

    private fun processM3u8(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val urls = VscoLoader.extractUrlsFromM3u()
            // Filter and download chunks...
        }
    }
}