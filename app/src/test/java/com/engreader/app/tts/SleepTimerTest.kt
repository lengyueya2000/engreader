package com.engreader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sleep timer's arithmetic.
 *
 * Two small pure functions, but they are what stands between a listener and the voice
 * still going at three in the morning, and both have an off-by-one that is easy to write
 * and hard to notice: the deadline has to be in the future by the chosen number of
 * minutes, and the label has to round *up*, or a timer with half a minute left reads
 * "0 分钟后停止" while the voice is plainly still going.
 */
class SleepTimerTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `the clock options land the chosen number of minutes ahead`() {
        assertEquals(now + 15 * minute, SleepTimer.deadline(SleepOption.Fifteen, now))
        assertEquals(now + 30 * minute, SleepTimer.deadline(SleepOption.Thirty, now))
        assertEquals(now + 60 * minute, SleepTimer.deadline(SleepOption.Sixty, now))
    }

    @Test
    fun `the non-clock options have no deadline`() {
        // Off is "no timer", and 读完本篇 ends on an event rather than on the clock, so
        // neither may be turned into an instant — a zero here is what tells the session
        // not to arm a countdown at all.
        assertEquals(0L, SleepTimer.deadline(SleepOption.Off, now))
        assertEquals(0L, SleepTimer.deadline(SleepOption.EndOfPiece, now))
    }

    @Test
    fun `a timer that is not armed says nothing`() {
        assertEquals("", SleepTimer.remainingLabel(0L, now))
        assertEquals("", SleepTimer.remainingLabel(-1L, now))
    }

    @Test
    fun `the label rounds up so a running timer never reads as zero`() {
        // The one that matters: 30 seconds left is still half a minute of listening, and
        // "0 分钟后停止" would read as "it has already stopped".
        assertEquals("1 分钟后停止", SleepTimer.remainingLabel(now + 30_000L, now))
        assertEquals("1 分钟后停止", SleepTimer.remainingLabel(now + 1L, now))
        assertEquals("1 分钟后停止", SleepTimer.remainingLabel(now + minute, now))
    }

    @Test
    fun `a whole number of minutes is not rounded up a minute too far`() {
        // The boundary the round-up is most likely to get wrong: exactly two minutes left
        // must say two, not three.
        assertEquals("2 分钟后停止", SleepTimer.remainingLabel(now + 2 * minute, now))
        assertEquals("3 分钟后停止", SleepTimer.remainingLabel(now + 2 * minute + 1L, now))
    }

    @Test
    fun `a deadline in the past is reported as about to stop`() {
        // The tick that reads the label can land after the timer fired; the label must
        // not go negative or blank out on the frame the pause happens.
        assertEquals("即将停止", SleepTimer.remainingLabel(now - 1L, now))
        assertEquals("即将停止", SleepTimer.remainingLabel(now, now))
    }

    @Test
    fun `the deadline and the label agree end to end`() {
        // The two functions are only ever used together: a deadline set now, read back
        // immediately, is the option's own number of minutes.
        val until = SleepTimer.deadline(SleepOption.Thirty, now)
        assertEquals("30 分钟后停止", SleepTimer.remainingLabel(until, now))
    }

    @Test
    fun `every option carries a label for the chips`() {
        // The listening screen renders the enum directly, so a blank label would draw an
        // unlabelled button rather than fail.
        SleepOption.entries.forEach {
            org.junit.Assert.assertTrue("$it has no label", it.label.isNotBlank())
        }
    }
}
