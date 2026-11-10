# Default download queue

The download dialog's **Queue default download** action saves the video ID,
source/account identity, and device screen dimensions immediately. It does not
require a populated quality list. The Downloads page's **Download queue** button
shows queued, resolving, downloading, waiting, and completed items, with retry
and cancellation controls. The player uses its existing circular control for
queued work as well as manual downloads.

The queue processes one video at a time: resolve its formats, select defaults,
check storage, finish video and audio, then resolve the next video. It does not
prefetch the next video's formats while transferring the current video. While
a batch is pending, the dialog defers automatic format loading; **Choose quality**
starts an explicit manual request. Manual transfers finish before queued work
starts. This limits concurrency; it is not a guarantee against upstream blocking
and does not make downloads identical to playback traffic.

Defaults use the largest video fitting the screen (or the smallest if none fits)
and the first preferred audio track. Combined video/audio remains usable when
separate audio is absent. The automatic queue leaves at least 500 MB of estimated
free space. It accounts for partial files, checks before transfer and during
transfer, and waits if size cannot be estimated or storage is insufficient.
Blocked or permanently failed items do not prevent eligible later items from
running. Ordinary transient connection failures pause the drain and retry with
WorkManager's exponential backoff, starting at 30 seconds.

Queue rows and representation identities are persisted in Room (schema 26).
WorkManager persists execution and reschedules after process death/reboot,
subject to Android's network, battery, and execution restrictions. Execution
uses a foreground data-sync notification while the UI can be closed. A force-stop
requires reopening the app before Android will run it again. Startup and periodic
recovery cover a process death between saving a row and scheduling work.

Media files stay in the ordinary download directories. A retry re-resolves URLs,
compares representation identity/known size/modification time, and resumes the
same track at its current file offset. A changed representation resets that
track before persisting its new identity; it is never appended to incompatible
bytes. The queue stores no signed URLs or credentials. Switching extraction
mode, instance, or account pauses that request until the original source is
restored and retried. Existing encrypted authentication handles renewed sessions.

Cancellation persists first, interrupts active requests/coroutines, closes file
writers, and removes unfinished items. Late progress/metadata cannot reactivate
a cancelled row. Completed files remain. Manual Resume All delegates managed
queue items back to the queue to avoid simultaneous writers.

Opening a video automatically plays its completed saved media without a choice
dialog. All required media tracks must be complete and present; unfinished,
missing, or subtitle-only downloads do not cause automatic offline playback.

Validation includes HTTP disconnect/resume with a refreshed URL, representation
identity and storage-boundary tests, completed-file checks, and API 25 tests that
migrate the previous database, preserve existing downloads, reopen persisted
queue state, and verify cancellation wins over late callbacks. Device testing is
still needed for vendor battery policies and full batch/offline playback behavior.
