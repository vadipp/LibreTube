package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.StatFs
import android.util.DisplayMetrics
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogDownloadBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.getWhileDigit
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.DownloadQueue
import com.github.libretube.helpers.DownloadQueuePolicy
import com.github.libretube.helpers.DownloadSelection
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.parcelable.DownloadData
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadDialog : DialogFragment() {
    private lateinit var videoId: String
    private var onDownloadConfirm = {}
    private var optionsGate: Job? = null
    private var sourceJob: Job? = null
    private var storageJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = arguments?.getString(IntentData.videoId)!!
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogDownloadBinding.inflate(layoutInflater)
        binding.videoTitle.text = arguments?.getString(IntentData.videoTitle).orEmpty()

        binding.chooseDownloadQuality.setOnClickListener {
            binding.chooseDownloadQuality.isGone = true
            binding.loadingProgress.isVisible = true
            binding.loadingStatus.setText(R.string.preparing_download_options)
            fetchAvailableSources(binding)
        }
        optionsGate = lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.downloadQueueDao().all().any { DownloadQueuePolicy.active(it.state) }
            }
            if (pending) {
                binding.loadingProgress.isGone = true
                binding.loadingStatus.setText(R.string.queue_manual_options)
                binding.chooseDownloadQuality.isVisible = true
            } else fetchAvailableSources(binding)
        }
        binding.queueDefaultDownload.setOnClickListener {
            optionsGate?.cancel()
            sourceJob?.cancel()
            binding.queueDefaultDownload.isEnabled = false
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            requireActivity().windowManager.defaultDisplay.getRealMetrics(metrics)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        DownloadQueue.enqueue(requireContext(), videoId, binding.videoTitle.text.toString(), metrics.widthPixels, metrics.heightPixels)
                    }
                    Toast.makeText(context, R.string.queue_queued, Toast.LENGTH_SHORT).show()
                    dismiss()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    binding.queueDefaultDownload.isEnabled = true
                    Toast.makeText(context, R.string.download_options_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        showVideoAlreadyDownloadedWarning(binding)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download)
            .setView(binding.root)
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .apply {
                getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    onDownloadConfirm.invoke()
                }
            }
    }

    private fun showVideoAlreadyDownloadedWarning(binding: DialogDownloadBinding) {
        lifecycleScope.launch {
            val alreadyDownloaded = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.downloadDao().getDownloadById(videoId)
            } != null

            binding.alreadyDownloadedCard.isVisible = alreadyDownloaded
        }
    }

    private fun fetchAvailableSources(binding: DialogDownloadBinding) {
        sourceJob = lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getDownloadStreams(videoId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.loadingProgress.isGone = true
                binding.loadingStatus.setText(R.string.download_options_failed)
                Log.e(TAG(), e.stackTraceToString())
                context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                return@launch
            }
            binding.loadingProgress.isGone = true
            binding.loadingStatus.isGone = true
            initDownloadOptions(binding, response)
            (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = true
        }
    }

    private fun initDownloadOptions(binding: DialogDownloadBinding, streams: Streams) {
        binding.videoTitle.text = streams.title

        val videoStreams = streams.videoStreams.filter {
            !it.url.isNullOrEmpty()
        }.filter { !it.format.orEmpty().contains("HLS") }.sortedByDescending {
            it.quality.getWhileDigit()
        }

        val audioStreams = streams.audioStreams.filter {
            !it.url.isNullOrEmpty()
        }
            .sortedBy {
                // prioritize main audio track types (lower role flag) over secondary/subbed ones
                PlayerHelper.getFullAudioRoleFlags(0, it.audioTrackType.orEmpty())
            }
            .sortedByDescending {
                it.quality.getWhileDigit()
            }

        val subtitles = streams.subtitles
            .filter { !it.url.isNullOrEmpty() && !it.name.isNullOrEmpty() }
            .sortedBy { it.name }

        if (subtitles.isEmpty()) binding.subtitleSpinner.isGone = true

        binding.videoSpinner.items = videoStreams.map {
            val fileSize = it.contentLength.takeIf { size -> size > 0 }
                ?.let { size -> Formatter.formatShortFileSize(context, size) }
            val codec = it.codec?.takeIf { value -> value.isNotBlank() }
                ?: it.mimeType?.substringAfter('/')?.uppercase() ?: it.format
            listOfNotNull(it.quality, codec,
                getString(R.string.audio_included).takeIf { _ -> it.videoOnly == false },
                fileSize?.let { size -> "($size)" }).joinToString(" ")
        }.toMutableList().also {
            it.add(0, getString(R.string.no_video))
        }

        binding.audioSpinner.items = audioStreams.map {
            val fileSize = it.contentLength
                .takeIf { l -> l > 0 }
                ?.let { cl -> Formatter.formatShortFileSize(context, cl) }
            val infoStr = listOfNotNull(it.audioTrackLocale, fileSize)
                .joinToString(", ")
            "${it.quality} ${it.format} ($infoStr)"
        }.toMutableList().also {
            it.add(0, getString(if (audioStreams.isEmpty() && videoStreams.any { stream -> stream.videoOnly == false })
                R.string.no_separate_audio else R.string.no_audio))
        }

        binding.subtitleSpinner.items = subtitles.map { it.name.orEmpty() }.toMutableList().also {
            it.add(0, getString(R.string.no_subtitle))
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        requireActivity().windowManager.defaultDisplay.getRealMetrics(metrics)
        binding.videoSpinner.selectedItemPosition =
            (DownloadSelection.videoIndex(videoStreams, metrics.widthPixels, metrics.heightPixels) ?: -1) + 1
        binding.audioSpinner.selectedItemPosition = if (audioStreams.isEmpty()) 0 else 1
        subtitles.indexOfFirst { it.code == getSel(SUBTITLE_LANGUAGE) }.takeIf { it >= 0 }?.let {
            binding.subtitleSpinner.selectedItemPosition = it + 1
        }

        fun refreshStorage() {
            storageJob?.cancel()
            storageJob = lifecycleScope.launch {
                val available = withContext(Dispatchers.IO) {
                    runCatching {
                        StatFs(DownloadHelper.getDownloadDir(requireContext(), DownloadHelper.VIDEO_DIR).toString())
                            .availableBytes
                    }.getOrNull()
                }
                val chosen = listOfNotNull(
                    videoStreams.getOrNull(binding.videoSpinner.selectedItemPosition - 1),
                    audioStreams.getOrNull(binding.audioSpinner.selectedItemPosition - 1)
                )
                val estimate = DownloadSelection.estimatedBytes(
                    chosen, streams.duration, binding.subtitleSpinner.selectedItemPosition > 0
                )
                val remaining = if (available != null && estimate != null) available - estimate else null
                val warning = available != null && (remaining ?: available) < DownloadSelection.LOW_SPACE_BYTES
                val text = if (available == null) getString(R.string.download_storage_unavailable) else {
                    val free = Formatter.formatShortFileSize(context, available)
                    when {
                        estimate == null -> getString(R.string.download_storage_unknown_size, free) +
                            if (warning) "\n" + getString(R.string.download_storage_low) else ""
                        remaining!! < 0 -> getString(R.string.download_storage_insufficient, free,
                            Formatter.formatShortFileSize(context, estimate))
                        else -> getString(R.string.download_storage_estimate, free,
                            Formatter.formatShortFileSize(context, estimate),
                            Formatter.formatShortFileSize(context, remaining)) +
                            if (warning) "\n" + getString(R.string.download_storage_low) else ""
                    }
                }
                binding.storageStatus.text = text
                binding.storageStatus.setTextColor(MaterialColors.getColor(binding.storageStatus,
                    if (warning) androidx.appcompat.R.attr.colorError
                    else com.google.android.material.R.attr.colorOnSurfaceVariant))
                binding.storageStatus.setTypeface(null, if (warning) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        binding.videoSpinner.setOnSelectionChangeListener { refreshStorage() }
        binding.audioSpinner.setOnSelectionChangeListener { refreshStorage() }
        binding.subtitleSpinner.setOnSelectionChangeListener { refreshStorage() }
        lifecycleScope.launch {
            while (isActive) {
                refreshStorage()
                delay(5000)
            }
        }

        onDownloadConfirm = onDownloadConfirm@{
            val videoPosition = binding.videoSpinner.selectedItemPosition - 1
            val audioPosition = binding.audioSpinner.selectedItemPosition - 1
            val subtitlePosition = binding.subtitleSpinner.selectedItemPosition - 1

            if (listOf(videoPosition, audioPosition, subtitlePosition).all { it == -1 }) {
                Toast.makeText(context, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
                return@onDownloadConfirm
            }

            val videoStream = videoStreams.getOrNull(videoPosition)
            val audioStream = audioStreams.getOrNull(audioPosition)
            val subtitle = subtitles.getOrNull(subtitlePosition)

            saveSelections(videoStream, audioStream, subtitle)

            val downloadData = DownloadData(
                videoId = videoId,
                videoFormat = videoStream?.format,
                videoQuality = videoStream?.quality,
                audioFormat = audioStream?.format,
                audioQuality = audioStream?.quality,
                audioLanguage = audioStream?.audioTrackLocale,
                subtitleCode = subtitle?.code
            )
            DownloadHelper.startDownloadService(requireContext(), downloadData)

            dismiss()
        }
    }

    /**
     * Save the download selection to the preferences
     */
    private fun saveSelections(
        videoStream: PipedStream?,
        audioStream: PipedStream?,
        subtitle: Subtitle?
    ) {
        PreferenceHelper.putString(SUBTITLE_LANGUAGE, subtitle?.code.orEmpty())
        PreferenceHelper.putString(VIDEO_DOWNLOAD_FORMAT, videoStream?.format.orEmpty())
        PreferenceHelper.putString(VIDEO_DOWNLOAD_QUALITY, videoStream?.quality.orEmpty())
        PreferenceHelper.putString(AUDIO_DOWNLOAD_FORMAT, audioStream?.format.orEmpty())
        PreferenceHelper.putString(AUDIO_DOWNLOAD_QUALITY, audioStream?.quality.orEmpty())
    }

    private fun getSel(key: String) = PreferenceHelper.getString(key, "")

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(DOWNLOAD_DIALOG_DISMISSED_KEY, bundleOf())
    }

    companion object {
        private const val VIDEO_DOWNLOAD_QUALITY = "video_download_quality"
        private const val VIDEO_DOWNLOAD_FORMAT = "video_download_format"
        private const val AUDIO_DOWNLOAD_QUALITY = "audio_download_quality"
        private const val AUDIO_DOWNLOAD_FORMAT = "audio_download_format"
        private const val SUBTITLE_LANGUAGE = "subtitle_download_language"

        const val DOWNLOAD_DIALOG_DISMISSED_KEY = "download_dialog_dismissed_key"
    }
}
