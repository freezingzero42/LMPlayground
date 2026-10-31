package com.druk.lmplayground.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** [ClockCalendarParsing] is pure java.time, so these run on the plain JVM. */
class ClockCalendarParsingTest {

    /** A Friday. */
    private val today = LocalDate.of(2026, 10, 30)

    private fun date(text: String) = ClockCalendarParsing.date(text, today)

    // ── Timer length ────────────────────────────────────────────────

    @Test
    fun `24 hour and 12 hour times`() {
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("6:30"))
        assertEquals(LocalTime.of(18, 45), ClockCalendarParsing.timeOfDay("18:45"))
        assertEquals(LocalTime.of(19, 0), ClockCalendarParsing.timeOfDay("7 pm"))
        assertEquals(LocalTime.of(19, 0), ClockCalendarParsing.timeOfDay("7PM"))
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("6:30 a.m."))
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("6.30"))
        assertEquals(LocalTime.of(15, 0), ClockCalendarParsing.timeOfDay("  3pm "))
    }

    @Test
    fun `twelve oclock edge cases and words`() {
        assertEquals(LocalTime.of(0, 0), ClockCalendarParsing.timeOfDay("12 am"))
        assertEquals(LocalTime.of(12, 0), ClockCalendarParsing.timeOfDay("12 pm"))
        assertEquals(LocalTime.NOON, ClockCalendarParsing.timeOfDay("noon"))
        assertEquals(LocalTime.MIDNIGHT, ClockCalendarParsing.timeOfDay("Midnight"))
    }

    @Test
    fun `invalid times are rejected`() {
        assertNull(ClockCalendarParsing.timeOfDay("25:00"))
        assertNull(ClockCalendarParsing.timeOfDay("6:75"))
        assertNull(ClockCalendarParsing.timeOfDay("13 pm"))
        assertNull(ClockCalendarParsing.timeOfDay("0 am"))
        assertNull(ClockCalendarParsing.timeOfDay("soon"))
        assertNull(ClockCalendarParsing.timeOfDay(""))
    }

    // ── Dates ───────────────────────────────────────────────────────

    @Test
    fun `today tomorrow and iso dates`() {
        assertEquals(today, date("today"))
        assertEquals(today, date("Tonight"))
        assertEquals(LocalDate.of(2026, 10, 31), date("tomorrow"))
        assertEquals(LocalDate.of(2026, 11, 6), date("2026-11-06"))
    }

    @Test
    fun `weekdays mean the next such day after today`() {
        assertEquals(LocalDate.of(2026, 11, 2), date("monday"))
        assertEquals(LocalDate.of(2026, 11, 2), date("Mon"))
        assertEquals(LocalDate.of(2026, 11, 2), date("next monday"))
        assertEquals(LocalDate.of(2026, 11, 2), date("on Monday"))
        assertEquals(LocalDate.of(2026, 11, 5), date("thurs"))
        // Today is a Friday, so "friday" is a week later.
        assertEquals(LocalDate.of(2026, 11, 6), date("friday"))
        assertEquals(LocalDate.of(2026, 10, 31), date("saturday"))
    }

    @Test
    fun `month and day, with or without a year`() {
        assertEquals(LocalDate.of(2026, 11, 6), date("Nov 6"))
        assertEquals(LocalDate.of(2026, 11, 6), date("November 6th"))
        assertEquals(LocalDate.of(2026, 11, 6), date("6 November"))
        assertEquals(LocalDate.of(2026, 12, 25), date("Dec. 25, 2026"))
        // Already passed this year: next year.
        assertEquals(LocalDate.of(2027, 3, 1), date("March 1"))
        assertEquals(LocalDate.of(2026, 10, 30), date("Oct 30"))
    }

    @Test
    fun `invalid dates are rejected`() {
        assertNull(date("someday"))
        assertNull(date("Feb 30"))
        assertNull(date("2026-13-01"))
        assertNull(date(""))
    }

    // ── Alarm repeat days ───────────────────────────────────────────

    @Test
    fun `times with seconds, iso date times and times inside phrases`() {
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("06:30:00"))
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("2026-10-31T06:30:00"))
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("2026-10-31T06:30:00-04:00"))
        assertEquals(LocalTime.of(6, 30), ClockCalendarParsing.timeOfDay("tomorrow at 6:30 am"))
        assertEquals(LocalTime.of(19, 0), ClockCalendarParsing.timeOfDay("7pm tonight"))
        assertEquals(LocalTime.NOON, ClockCalendarParsing.timeOfDay("tomorrow at noon"))
        // A bare number in a phrase is not a time.
        assertNull(ClockCalendarParsing.timeOfDay("on the 6th"))
    }

    @Test
    fun `time is split out of a phrase`() {
        assertEquals(LocalTime.of(15, 0) to "friday", ClockCalendarParsing.timeInPhrase("Friday at 3 pm"))
        assertEquals(LocalTime.of(18, 30) to "tomorrow", ClockCalendarParsing.timeInPhrase("tomorrow 18:30"))
        assertEquals(LocalTime.of(9, 0) to "nov 6", ClockCalendarParsing.timeInPhrase("Nov 6 at 9am"))
        assertNull(ClockCalendarParsing.timeInPhrase("Nov 6"))
        assertNull(ClockCalendarParsing.timeInPhrase("2026-11-06"))
    }

    @Test
    fun `date fields that also carry a time`() {
        assertEquals(LocalDate.of(2026, 11, 6) to LocalTime.of(15, 0), ClockCalendarParsing.dateAndTime("friday at 3 pm", today))
        assertEquals(LocalDate.of(2026, 10, 31) to LocalTime.of(18, 30), ClockCalendarParsing.dateAndTime("tomorrow 18:30", today))
        assertEquals(
            LocalDate.of(2026, 11, 6) to LocalTime.of(15, 0),
            ClockCalendarParsing.dateAndTime("2026-11-06T15:00:00", today),
        )
        assertEquals(LocalDate.of(2026, 11, 6) to null, ClockCalendarParsing.dateAndTime("Nov 6", today))
        assertNull(ClockCalendarParsing.dateAndTime("at 3 pm", today))
    }

    @Test
    fun `timer lengths written as text`() {
        assertEquals(600, ClockCalendarParsing.durationSeconds("10 minutes"))
        assertEquals(600, ClockCalendarParsing.durationSeconds("10 min"))
        assertEquals(600, ClockCalendarParsing.durationSeconds("10"))
        assertEquals(5_400, ClockCalendarParsing.durationSeconds("1h 30m"))
        assertEquals(5_400, ClockCalendarParsing.durationSeconds("1 hour and 30 minutes"))
        assertEquals(5_400, ClockCalendarParsing.durationSeconds("1.5 hours"))
        assertEquals(90, ClockCalendarParsing.durationSeconds("90 seconds"))
        assertEquals(5_400, ClockCalendarParsing.durationSeconds("1:30:00"))
        assertEquals(300, ClockCalendarParsing.durationSeconds("05:00"))
        assertEquals(600, ClockCalendarParsing.durationSeconds("for 10 minutes"))
    }

    @Test
    fun `unclear or too long timer text is rejected`() {
        assertNull(ClockCalendarParsing.durationSeconds("a while"))
        assertNull(ClockCalendarParsing.durationSeconds("10 minutes for pasta"))
        assertNull(ClockCalendarParsing.durationSeconds("25 hours"))
        assertNull(ClockCalendarParsing.durationSeconds("0 minutes"))
        assertNull(ClockCalendarParsing.durationSeconds(""))
    }

    @Test
    fun `timer lengths in words`() {
        assertEquals(1_200, ClockCalendarParsing.durationSeconds("20 minutes"))
        assertEquals(1_200, ClockCalendarParsing.durationSeconds("twenty minutes"))
        assertEquals(1_500, ClockCalendarParsing.durationSeconds("twenty-five minutes"))
        assertEquals(1_500, ClockCalendarParsing.durationSeconds("Twenty five minutes"))
        assertEquals(1_800, ClockCalendarParsing.durationSeconds("half an hour"))
        assertEquals(3_600, ClockCalendarParsing.durationSeconds("an hour"))
        assertEquals(5_400, ClockCalendarParsing.durationSeconds("an hour and a half"))
        assertEquals(60, ClockCalendarParsing.durationSeconds("a minute"))
        assertEquals(3_900, ClockCalendarParsing.durationSeconds("one hour and five minutes"))
        assertEquals(45, ClockCalendarParsing.durationSeconds("forty-five seconds"))
    }

    @Test
    fun `every 24 hour time parses to the right time`() {
        for (h in 0..23) for (m in 0..59) {
            val text = "%02d:%02d".format(h, m)
            assertEquals(text, LocalTime.of(h, m), ClockCalendarParsing.timeOfDay(text))
        }
    }

    @Test
    fun `am pm sent anyway is converted to the same 24 hour time`() {
        for (h in 1..12) for (ap in listOf("am", "pm")) {
            val hour24 = (h % 12) + if (ap == "pm") 12 else 0
            assertEquals("$h:00 $ap", LocalTime.of(hour24, 0), ClockCalendarParsing.timeOfDay("$h:00 $ap"))
        }
    }

    @Test
    fun `the guide in the tool descriptions matches the parser`() {
        // Every "x = HH:MM" pair the model is shown must agree with how the app reads both sides.
        val pairs = Regex("""((?:\d{1,2}(?::\d{2})? [ap]m)|noon \(12 pm\)|midnight \(12 am\)) = (\d{2}:\d{2})""")
            .findAll(ClockCalendarParsing.TIME_24H_GUIDE).toList()
        assertTrue(pairs.size >= 12)
        for (p in pairs) {
            val left = p.groupValues[1].replace(Regex(""".*\((.*)\)"""), "$1")
            assertEquals(p.value, ClockCalendarParsing.timeOfDay(p.groupValues[2]), ClockCalendarParsing.timeOfDay(left))
        }
    }

}
