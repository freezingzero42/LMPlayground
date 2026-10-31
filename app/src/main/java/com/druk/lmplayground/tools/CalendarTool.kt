package com.druk.lmplayground.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.druk.llamacpp.tools.Tool
import com.druk.lmplayground.App
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * add_calendar_event: hands the event to the phone's Calendar app through a
 * standard Android intent (a filled-in "new event" screen the user saves),
 * so LM Playground never reads or writes the calendar itself and needs no
 * calendar permission.
 *
 * Small on-device models are sloppy with arguments, so the tool accepts the
 * common variations: other field names, text where a number was expected,
 * ISO date-times and a time inside the date ("friday at 3 pm").
 */

/** Opens another app for a tool, refusing politely when that can't work. */
internal object ClockCalendarLauncher {

    /**
     * Start [intent] and return null on success, or an error message for the
     * model. Android blocks apps in the background from opening other apps,
     * so the request is refused while LM Playground isn't on screen.
     */
    fun launch(context: Context, intent: Intent, appKind: String): String? {
        val app = context.applicationContext as? App
        if (app != null && !app.isAppInForeground) {
            return "LM Playground is in the background, so Android won't let it open the $appKind app. " +
                "Ask the user to open LM Playground and try again."
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (_: ActivityNotFoundException) {
            "No $appKind app on this phone can handle this request."
        } catch (e: SecurityException) {
            "Android refused to open the $appKind app: ${e.message}"
        }
    }

    fun error(message: String): String = JSONObject().put("error", message).toString()

    fun opened(summary: String, note: String): String =
        JSONObject().put("status", "done").put("summary", summary).put("note", note).toString()

    fun args(arguments: String): JSONObject? = try {
        JSONObject(arguments.ifBlank { "{}" })
    } catch (_: JSONException) {
        null
    }
}

/** The first of [keys] present with a non-blank value, as text ("" if none). */
internal fun JSONObject.text(vararg keys: String): String {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)
        val s = when (v) {
            is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
            else -> v.toString()
        }.trim()
        if (s.isNotEmpty()) return s
    }
    return ""
}

/** The first of [keys] holding a plain number (also "10" as text), or null. */
internal fun JSONObject.number(vararg keys: String): Double? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        when (val v = opt(k)) {
            is Number -> return v.toDouble()
            is String -> v.trim().toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

/** `add_calendar_event`: a filled-in "new event" screen in the Calendar app. */
class AddCalendarEventTool(
    private val context: Context,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : Tool {
    override val name = "add_calendar_event"
    override val description =
        "Add an event or reminder to the phone's calendar, for requests like \"add dentist on Friday at 3 pm\". " +
            "Opens the Calendar app's new-event screen with the details filled in; the user taps Save to keep it. " +
            "For the date you can write \"today\", \"tomorrow\", a weekday such as \"friday\", \"Nov 6\" or \"2026-11-06\"; " +
            "call get_date_time first if you need today's date. Leave out start_time for an all-day event. " +
            ClockCalendarParsing.TIME_24H_GUIDE +
            "Example: \"dentist on Friday at 3 pm\" -> " +
            "{\"title\": \"Dentist\", \"date\": \"friday\", \"start_time\": \"15:00\", \"duration_minutes\": 60}. " +
            "Another: {\"title\": \"Mom's birthday\", \"date\": \"Nov 14\"}."
    override val parametersSchema = """{"type":"object","properties":{""" +
        """"title":{"type":"string","description":"What the event is, e.g. \"Dentist\""},""" +
        """"date":{"type":"string","description":"\"today\", \"tomorrow\", a weekday like \"friday\", \"Nov 6\" or \"2026-11-06\""},""" +
        """"start_time":{"type":"string","description":"Start time, 24-hour HH:MM, e.g. \"15:00\" for 3 pm. Leave out for all day."},""" +
        """"end_time":{"type":"string","description":"End time, 24-hour HH:MM, e.g. \"16:30\" for 4:30 pm. Optional."},""" +
        """"duration_minutes":{"type":"integer","description":"Length in minutes when there is no end_time. Default 60."},""" +
        """"location":{"type":"string","description":"Where it happens. Optional."},""" +
        """"notes":{"type":"string","description":"Extra details. Optional."}""" +
        """},"required":["title","date"]}"""

    override fun execute(arguments: String): String {
        val args = ClockCalendarLauncher.args(arguments)
            ?: return ClockCalendarLauncher.error(
                "Arguments must be a JSON object, e.g. {\"title\": \"Dentist\", \"date\": \"friday\", \"start_time\": \"15:00\"}."
            )
        val title = args.text("title", "name", "event", "summary").take(200)
        if (title.isEmpty()) return ClockCalendarLauncher.error("\"title\" is required, e.g. {\"title\": \"Dentist\", ...}.")
        val today = clock()
        // The date field may carry the time too ("friday at 3 pm", "2026-11-06T15:00").
        val (date, timeInDate) = ClockCalendarParsing.dateAndTime(args.text("date", "day", "when", "start"), today)
            ?: return ClockCalendarLauncher.error(
                "\"date\" must be \"today\", \"tomorrow\", a weekday like \"friday\", \"Nov 6\" or \"2026-11-06\"."
            )
        val startText = args.text("start_time", "time", "start", "from")
        val endText = args.text("end_time", "end", "to", "until")

        val zone = ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        args.text("location", "place", "where").takeIf { it.isNotEmpty() }
            ?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it.take(200)) }
        args.text("notes", "description", "details").takeIf { it.isNotEmpty() }
            ?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it.take(2000)) }

        val start = if (startText.isNotEmpty()) {
            ClockCalendarParsing.timeOfDay(startText)
                ?: return ClockCalendarLauncher.error("\"start_time\" must be a 24-hour time HH:MM, e.g. \"15:00\" for 3 pm.")
        } else {
            timeInDate
        }

        val dayText = date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH))
        val summary: String
        if (start == null) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, date.atStartOfDay(zone).toInstant().toEpochMilli())
                .putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            summary = "\"$title\" on $dayText, all day"
        } else {
            val begin = date.atTime(start)
            val end = if (endText.isNotEmpty()) {
                val endTime = ClockCalendarParsing.timeOfDay(endText)
                    ?: return ClockCalendarLauncher.error("\"end_time\" must be a 24-hour time HH:MM, e.g. \"16:00\" for 4 pm.")
                // An end before the start means the event runs past midnight.
                date.atTime(endTime).let { if (it.isAfter(begin)) it else it.plusDays(1) }
            } else {
                val minutes = durationMinutes(args) ?: return ClockCalendarLauncher.error(
                    "\"duration_minutes\" must be a number of minutes between 1 and 10080, e.g. 60."
                )
                begin.plusMinutes(minutes)
            }
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.atZone(zone).toInstant().toEpochMilli())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
            val h24 = DateTimeFormatter.ofPattern("HH:mm")
            val h12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            summary = "\"$title\" on $dayText, ${begin.format(h24)}–${end.format(h24)} " +
                "(${begin.format(h12)}–${end.format(h12)})"
        }
        ClockCalendarLauncher.launch(context, intent, "Calendar")?.let { return ClockCalendarLauncher.error(it) }
        return ClockCalendarLauncher.opened(
            summary,
            "The Calendar app is showing this new event, filled in. It is NOT saved until the user taps Save there.",
        )
    }

    /** Minutes from duration_minutes (number) or a duration as text ("90 minutes"); default 60. */
    private fun durationMinutes(args: JSONObject): Long? {
        val minutes = args.number("duration_minutes", "duration", "minutes", "length")?.let { Math.round(it) }
            ?: args.text("duration", "length").takeIf { it.isNotEmpty() }
                ?.let { text -> ClockCalendarParsing.durationSeconds(text)?.let { it / 60L } }
            ?: if (args.text("duration_minutes", "duration", "length").isEmpty()) 60L else null
        return minutes?.takeIf { it in 1..(7 * 24 * 60) }
    }
}
