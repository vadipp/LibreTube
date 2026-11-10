package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.helpers.DownloadQueue
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadQueueDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val rows = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val scroll = ScrollView(requireContext()).apply { addView(rows) }
        lifecycleScope.launch {
            Database.downloadQueueDao().observe().collect { entries ->
                rows.removeAllViews()
                if (entries.isEmpty()) rows.addView(TextView(context).apply { setText(R.string.download_queue_empty) })
                for (entry in entries.filter { it.state != "CANCELLED" }.takeLast(100)) {
                    rows.addView(MaterialButton(requireContext()).apply {
                        val progress = if (entry.totalBytes > 0 && entry.downloadedBytes > 0)
                            " • " + Formatter.formatShortFileSize(context, entry.downloadedBytes) + " / " + Formatter.formatShortFileSize(context, entry.totalBytes) else ""
                        text = "${entry.title.ifBlank { entry.videoId }}\n${queueState(entry.state)}$progress"
                        isAllCaps = false
                        setOnClickListener {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(entry.title.ifBlank { entry.videoId })
                                .setItems(arrayOf(getString(R.string.queue_retry), getString(R.string.cancel_video_download_action))) { _, action ->
                                    lifecycleScope.launch {
                                        withContext(Dispatchers.IO) {
                                            if (action == 0) {
                                                Database.downloadQueueDao().retry(entry.videoId)
                                                DownloadQueue.schedule(requireContext(), restart = true)
                                            } else DownloadQueue.cancel(entry.videoId)
                                        }
                                    }
                                }.setNegativeButton(R.string.cancel, null).show()
                        }
                    })
                }
            }
        }
        return MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.download_queue)
            .setView(scroll).setPositiveButton(R.string.okay, null).create()
    }

    private fun queueState(state: String) = getString(when (state) {
        "RESOLVING" -> R.string.preparing_download_options
        "DOWNLOADING" -> R.string.queue_downloading
        "RETRY" -> R.string.queue_retry_wait
        "SPACE" -> R.string.queue_waiting_space
        "SIZE" -> R.string.queue_waiting_size
        "ATTENTION" -> R.string.queue_attention
        "INSTANCE" -> R.string.queue_instance_changed
        "CANCELLING" -> R.string.queue_cancelling
        "COMPLETE" -> R.string.download_completed
        else -> R.string.queue_queued
    })
}
