package com.github.libretube

import com.github.libretube.helpers.DownloadProgressTracker as Tracker
import com.github.libretube.obj.DownloadStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTrackerTest {
    @After fun cleanup() = Tracker.clear()

    @Test fun aggregatesBothTracksAndWaitsForBothToComplete() {
        Tracker.preparing("example")
        assertNull(Tracker.downloads.value.getValue("example").percent)
        Tracker.items("example", mapOf(1 to 900L, 2 to 100L))
        Tracker.status("example", 1, DownloadStatus.Progress(450, 450, 900))
        Tracker.status("example", 2, DownloadStatus.Progress(50, 50, 100))
        assertEquals(50, Tracker.downloads.value.getValue("example").percent)
        Tracker.status("example", 1, DownloadStatus.Completed)
        assertEquals(95, Tracker.downloads.value.getValue("example").percent)
        Tracker.status("example", 2, DownloadStatus.Completed)
        assertFalse("example" in Tracker.downloads.value)
    }

    @Test fun unknownSizesRemainIndeterminateAndLateEventsCannotResurrectCancelledWork() {
        Tracker.items("example", mapOf(1 to -1L))
        Tracker.status("example", 1, DownloadStatus.Progress(10, 10, -1))
        assertNull(Tracker.downloads.value.getValue("example").percent)
        Tracker.remove("example")
        Tracker.status("example", 1, DownloadStatus.Progress(10, 20, 100))
        assertFalse("example" in Tracker.downloads.value)
    }
}
