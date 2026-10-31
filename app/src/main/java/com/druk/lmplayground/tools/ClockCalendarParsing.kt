package com.druk.lmplayground.tools

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/**
 * Lenient parsing of the dates, times and durations models pass to the
 * timer, alarm and calendar tools.
 *
 * Small models are unreliable at date arithmetic, so the tools accept the
 * words people actually use ("tomorrow", "friday", "6:30 pm", "noon") and
 * resolve them here against the phone's clock, instead of asking the model
 * to produce an exact ISO date.
 *
 * Pure java.time with no Android dependencies, so it is JVM-tested.
 */
object ClockCalendarParsing {

    /** Longest timer the system Clock intent accepts: 24 hours. */
    const val MAX_TIMER_SECONDS = 24 * 60 * 60

    /**
     * The one time format the tools ask models for: 24-hour HH:MM. Spelled
     * out in the tool descriptions so a model converts "7 pm" to "19:00"
     * before calling; am/pm text is still understood if a model sends it.
     */
    const val TIME_24H_GUIDE =
        "Write every time in 24-hour (military) time as HH:MM, converting first. " +
            "Midnight (12 am) = 00:00, 1 am = 01:00, 6:30 am = 06:30, 11 am = 11:00, noon (12 pm) = 12:00, " +
            "1 pm = 13:00, 2 pm = 14:00, 3 pm = 15:00, 4 pm = 16:00, 5 pm = 17:00, 6 pm = 18:00, " +
            "7 pm = 19:00, 8 pm = 20:00, 9 pm = 21:00, 10 pm = 22:00, 11 pm = 23:00. " +
            "Rule: for pm add 12 to the hour, except 12 pm stays 12; for 12 am use 00. Keep the minutes (10:15 pm = 22:15). "

    private val TIME = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(?::\d{2}(?:\.\d+)?)?\s*(a\.?m\.?|p\.?m\.?)?$""")

    /** A time somewhere inside a longer phrase: "6:30", "6:30:00", "7 pm", "7pm". */
    private val TIME_IN_TEXT = Regex(
        """(?<![\d:.])(\d{1,2})(?:([:.])(\d{2})(?::\d{2})?)?\s*(a\.?m\.?|p\.?m\.?)?(?![\d:])""",
    )

    /**
     * A time of day: "6:30", "18:45", "6pm", "6:30 PM", "6.30", "06:30:00",
     * "noon", "midnight", an ISO date-time ("2026-10-31T06:30:00"), or a
     * phrase containing one ("tomorrow at 6:30 am"). Null if there is none.
     */
    fun timeOfDay(text: String): LocalTime? {
        val t = text.trim().lowercase()
        when (t) {
            "noon", "midday" -> return LocalTime.NOON
            "midnight" -> return LocalTime.MIDNIGHT
        }
        exactTime(t)?.let { return it }
        // "2026-10-31T06:30:00": the part after the T.
        if ('t' in t) exactTime(t.substringAfter('t').substringBefore('+').substringBefore('z'))?.let { return it }
        return timeInPhrase(t)?.first
    }

    /**
     * The first time inside [text] and the text with it removed, e.g.
     * "friday at 3 pm" -> (15:00, "friday"). A bare number only counts as a
     * time with am/pm or a ":"/"." minute part, so dates like "Nov 6" or
     * "2026-11-06" aren't mistaken for times.
     */
    fun timeInPhrase(text: String): Pair<LocalTime, String>? {
        val t = text.lowercase()
        when {
            Regex("""\bnoon\b|\bmidday\b""").containsMatchIn(t) ->
                return LocalTime.NOON to t.replace(Regex("""(?:\bat\s+)?\b(?:noon|midday)\b"""), " ").trim()
            Regex("""\bmidnight\b""").containsMatchIn(t) ->
                return LocalTime.MIDNIGHT to t.replace(Regex("""(?:\bat\s+)?\bmidnight\b"""), " ").trim()
        }
        for (m in TIME_IN_TEXT.findAll(t)) {
            val hasMinutes = m.groupValues[2].isNotEmpty()
            val hasMeridiem = m.groupValues[4].isNotEmpty()
            if (!hasMinutes && !hasMeridiem) continue
            val time = exactTime(m.value.trim()) ?: continue
            // Drop the time and a preceding "at".
            val before = t.substring(0, m.range.first).replace(Regex("""\bat\s*$"""), "")
            val rest = (before + " " + t.substring(m.range.last + 1)).replace(Regex("""\s+"""), " ").trim()
            return time to rest
        }
        return null
    }

    private fun exactTime(t: String): LocalTime? {
        val m = TIME.matchEntire(t.trim()) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val meridiem = m.groupValues[3].replace(".", "")
        if (minute > 59) return null
        when (meridiem) {
            "am" -> {
                if (hour !in 1..12) return null
                if (hour == 12) hour = 0
            }
            "pm" -> {
                if (hour !in 1..12) return null
                if (hour != 12) hour += 12
            }
            else -> if (hour > 23) return null
        }
        return LocalTime.of(hour, minute)
    }

    private val DURATION_PART = Regex(
        """(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""",
    )
    private val CLOCK_DURATION = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})$""")

    /**
     * A timer length written as text, in seconds: "10 minutes", "1h 30m",
     * "1 hour and 30 minutes", "1.5 hours", "90 seconds", "1:30:00" (h:m:s),
     * "05:00" (m:s), or a bare number meaning minutes ("10"). Null if it
     * isn't one or isn't between 1 second and 24 hours.
     */
    fun durationSeconds(text: String): Int? {
        val t = numberWordsToDigits(text.trim().lowercase()).removePrefix("for ").removePrefix("in ").trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { minutes -> return checkTimer(Math.round(minutes * 60)) }
        CLOCK_DURATION.matchEntire(t)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0
            return checkTimer(h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong())
        }
        val parts = DURATION_PART.findAll(t).toList()
        if (parts.isEmpty()) return null
        // Everything except the parts and joining words must be gone.
        val leftover = DURATION_PART.replace(t, " ").replace(Regex("""\b(and|,)\b|,"""), " ").trim()
        if (leftover.isNotEmpty()) return null
        var total = 0.0
        for (p in parts) {
            val n = p.groupValues[1].toDouble()
            total += when (p.groupValues[2].first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
        }
        return checkTimer(Math.round(total))
    }

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val NUMBER_WORD = Regex(
        """\b(?:(${TENS.keys.joinToString("|")})(?:[\s-]+(${UNITS.drop(1).take(9).joinToString("|")}))?|(${UNITS.joinToString("|")}))\b""",
    )

    /**
     * Spelled-out amounts as digits, so "twenty-five minutes", "half an
     * hour", "an hour and a half" and "a minute" read like "25 minutes".
     */
    internal fun numberWordsToDigits(text: String): String {
        var t = text
            .replace(Regex("""\bhalf an? hour\b"""), "30 minutes")
            .replace(Regex("""\ban? hour and a half\b"""), "90 minutes")
            .replace(Regex("""\bhalf a minute\b"""), "30 seconds")
            .replace(Regex("""\b(an?)\s+(hour|minute|second)\b"""), "1 $2")
        t = NUMBER_WORD.replace(t) { m ->
            val tens = m.groupValues[1]
            val unit = m.groupValues[2]
            val single = m.groupValues[3]
            when {
                tens.isNotEmpty() -> (TENS.getValue(tens) + (if (unit.isEmpty()) 0 else UNITS.indexOf(unit))).toString()
                else -> UNITS.indexOf(single).toString()
            }
        }
        return t
    }

    private fun checkTimer(seconds: Long): Int? = seconds.takeIf { it in 1..MAX_TIMER_SECONDS }?.toInt()

    private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        for (day in DayOfWeek.entries) {
            val full = day.name.lowercase()
            put(full, day)
            put(full.take(3), day)
        }
        put("tues", DayOfWeek.TUESDAY)
        put("wednes", DayOfWeek.WEDNESDAY)
        put("thur", DayOfWeek.THURSDAY)
        put("thurs", DayOfWeek.THURSDAY)
    }

    private val MONTHS: Map<String, Month> = buildMap {
        for (month in Month.entries) {
            val full = month.name.lowercase()
            put(full, month)
            put(full.take(3), month)
        }
        put("sept", Month.SEPTEMBER)
    }

    /** A weekday name ("friday", "fri", "Thurs"), or null. */
    fun weekday(text: String): DayOfWeek? = WEEKDAYS[text.trim().lowercase().removeSuffix(".")]

    /**
     * A calendar date relative to [today]:
     * - "2026-11-06" (ISO)
     * - "today", "tomorrow"
     * - a weekday, optionally with "this" or "next": the next such day after
     *   today ("friday" said on a Friday means a week later; use "today" for
     *   today)
     * - "Nov 6", "November 6th", "6 November", optionally with a year;
     *   without a year, this year's date, or next year's if it has passed
     */
    fun date(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().lowercase().replace(",", " ").replace(Regex("\\s+"), " ")
        when (t) {
            "today", "tonight" -> return today
            "tomorrow" -> return today.plusDays(1)
        }
        try {
            return LocalDate.parse(t)
        } catch (_: DateTimeParseException) {
        }
        val words = t.split(' ')
        val dayWord = if (words.size == 2 && words[0] in setOf("this", "next", "on")) words[1] else t
        weekday(dayWord)?.let { return today.with(TemporalAdjusters.next(it)) }
        return monthDay(words, today)
    }

    /**
     * A date that may carry a time too, as models often put both in one
     * field: "friday at 3 pm", "tomorrow 18:30", "2026-11-06T15:00:00".
     * Returns the date and the time (null when there was no time), or null
     * if there's no date.
     */
    fun dateAndTime(text: String, today: LocalDate): Pair<LocalDate, LocalTime?>? {
        date(text, today)?.let { return it to null }
        val t = text.trim().lowercase()
        // ISO date-time: "2026-11-06T15:00:00".
        Regex("""^(\d{4}-\d{2}-\d{2})t(.+)$""").matchEntire(t)?.let { m ->
            val d = date(m.groupValues[1], today) ?: return null
            return d to timeOfDay(m.groupValues[2])
        }
        val (time, rest) = timeInPhrase(t) ?: return null
        val d = date(rest, today) ?: return null
        return d to time
    }

    private fun monthDay(words: List<String>, today: LocalDate): LocalDate? {
        if (words.size !in 2..3) return null
        fun dayOf(w: String) = w.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th")
            .toIntOrNull()
        val (month, day) = MONTHS[words[0].removeSuffix(".")]?.let { it to dayOf(words[1]) }
            ?: MONTHS[words[1].removeSuffix(".")]?.let { it to dayOf(words[0]) }
            ?: return null
        if (day == null) return null
        val year = words.getOrNull(2)?.toIntOrNull()
        return try {
            if (year != null) {
                LocalDate.of(year, month, day)
            } else {
                val thisYear = LocalDate.of(today.year, month, day)
                if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
            }
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}
