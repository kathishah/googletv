package com.googletv.screensaver.playback

import java.io.IOException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFailureRulesTest {
    @Test
    fun readerTransportFailureIsRetryableBeforeTheFirstFrame() {
        assertEquals(VideoFailure.STALLED, VideoFailureRules.classify(SocketException("reset")))
    }

    @Test
    fun corruptOrUnsupportedClipIsPermanentEvenBeforeDownloadCompletes() {
        assertEquals(VideoFailure.UNPLAYABLE, VideoFailureRules.classify(IOException("decoder")))
    }

    @Test
    fun playerErrorWithoutAReaderFailureIsTreatedAsUnplayable() {
        assertEquals(VideoFailure.UNPLAYABLE, VideoFailureRules.classify(null))
    }
}
