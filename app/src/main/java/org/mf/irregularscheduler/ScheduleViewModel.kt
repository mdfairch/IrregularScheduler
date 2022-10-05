/*
 * Copyright 2022 Mark Fairchild.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.mf.irregularscheduler

import android.app.Application
import android.content.SharedPreferences
import android.text.Html
import android.text.Spanned
import android.text.SpannedString
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.*
import kotlin.collections.ArrayList

typealias Flagger = (r:IntRange, v:Boolean) -> Unit

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs : SharedPreferences by lazy{PreferenceManager.getDefaultSharedPreferences(application)}
    private fun assumeDaytime() : Boolean = prefs.getBoolean("assumeDaytime", true)
    private fun defaultDuration() : Int = prefs.getString("defaultDuration", "8")?.toIntOrNull() ?: 8

    data class Shift(var dayOfWeek: DayOfWeek, var startTime: LocalTime, var endTime: LocalTime)

    data class ScheduleEntry(var startTime: LocalDateTime, var endTime: LocalDateTime) {
        fun getStartAsDate(zoneId : ZoneId) : Date = Date.from(startTime.atZone(zoneId).toInstant())
        fun getEndAsDate(zoneId : ZoneId) : Date = Date.from(endTime.atZone(zoneId).toInstant())
    }

    data class Schedule(val shifts: List<ScheduleEntry>)
    private val emptySchedule = Schedule(emptyList())

    private val time1Pattern = "(?<timeCode1>\\d{1,2}[:.]\\d{2}|\\d{1,4})\\s*(?<period1>am|pm)?"
    private val time2Pattern = "(?<timeCode2>\\d{1,2}[:.]\\d{2}|\\d{1,4})\\s*(?<period2>am|pm)?"
    private val durationPattern = "$time1Pattern\\s*((-|to)?\\s*$time2Pattern)?"
    private val dayPattern = "(?<day>m|t|w|r|f|sa|su)"
    private val shiftPattern by lazy { "($dayPattern\\s*$durationPattern)" }
    private val schedulePattern by lazy { "^\\s*($shiftPattern\\s*)*\$" }
    private val shiftRegex by lazy { Regex(shiftPattern, RegexOption.IGNORE_CASE) }
    private val scheduleRegex by lazy { Regex(schedulePattern, RegexOption.IGNORE_CASE) }
    private val timeCodeCleaner by lazy { Regex("\\s|[:.]") }
    private var _foundErrors = false
    private var _schedule = emptySchedule

    var schedule: Schedule
        get() = _schedule
        set(value) {
            _schedule = value
        }

    var foundErrors: Boolean
        get() = _foundErrors
        set(value) {
            _foundErrors = value
        }

    fun clear() {
        _schedule = emptySchedule
        _foundErrors = false
    }

    /**
     * Converts a String into a Schedule.
     * Any portions of text that can't be parsed will be ignored.
     */
    fun parseText(text : String, dayOffset: Int, flagger : Flagger) {
        if (text.isBlank()) {
            clear()
        } else {
            val matches = shiftRegex.findAll(text).toList()
            val shiftsInformal = matches.mapNotNull {m -> matchToShift(m, flagger) }.toList()

            //Log.i("ScheduleViewModel.textToSchedule", "Shifts: $shiftsInformal")
            val startingDate = LocalDate.now().plusDays(dayOffset.toLong())

            schedule = shiftsToSchedule(shiftsInformal, startingDate)
            //Log.i("ScheduleViewModel.textToSchedule", "Schedule $schedule")

            foundErrors = !scheduleRegex.matches(text) || shiftsInformal.size < matches.size
        }
    }

    private fun shiftsToSchedule(shiftsInformal: List<Shift>, startingDate: LocalDate) : Schedule {
        var workDay = startingDate

        val shifts = ArrayList<ScheduleEntry>(shiftsInformal.size)

        for (shift in shiftsInformal) {
            workDay = workDay.with(TemporalAdjusters.next(shift.dayOfWeek))

            val startTime = LocalDateTime.of(workDay, shift.startTime)
            val endTime = if (shift.endTime >= shift.startTime)
                LocalDateTime.of(workDay, shift.endTime)
                else LocalDateTime.of(workDay.plusDays(1), shift.endTime)

            //val duration = ChronoUnit.HOURS.between(shift.startTime, shift.endTime)
            //Log.i("ScheduleViewModel.shiftsToSchedule", "$workDay $duration $startTime $endTime")
            shifts.add(ScheduleEntry(startTime, endTime))
        }

        return Schedule(shifts)
    }

    private fun breakdownTimeCode(timeCode : String) : Pair<Int, Int> {
        when (timeCode.length) {
            in 1..2 -> {
                val hours = timeCode.toInt()
                return Pair(hours, 0)
            }
            3 -> {
                val hours = timeCode.subSequence(0, 1).toString().toInt()
                val minutes = timeCode.subSequence(1, 3).toString().toInt()
                return Pair(hours, minutes)
            }
            4 -> {
                val hours = timeCode.subSequence(0, 2).toString().toInt()
                val minutes = timeCode.subSequence(2, 4).toString().toInt()
                return Pair(hours, minutes)
            }
            else -> throw NumberFormatException("Too many digits in time code: $timeCode")
        }
    }

    private fun matchToShift(match : MatchResult, flagger : Flagger) : Shift? {
        try {
            //Log.i("ScheduleViewModel.matchToShift", "match = ${match.value}")
            val dayString =
                match.groups["day"]?.value ?: throw NumberFormatException("Invalid day code")

            val startTimeCode = match.groups["timeCode1"]?.value?.replace(timeCodeCleaner, "")
                ?: throw NumberFormatException("Missing start time")
            val startPeriod = match.groups["period1"]?.value

            val endTimeCode = match.groups["timeCode2"]?.value?.replace(timeCodeCleaner, "")
            val endPeriod = match.groups["period2"]?.value

            //Log.i("ScheduleViewModel.matchToShift", "$dayString $startTimeCode $startPeriod $endTimeCode $endPeriod")

            val day = when (dayString.lowercase()) {
                "m" -> DayOfWeek.MONDAY
                "t" -> DayOfWeek.TUESDAY
                "w" -> DayOfWeek.WEDNESDAY
                "r" -> DayOfWeek.THURSDAY
                "f" -> DayOfWeek.FRIDAY
                "sa" -> DayOfWeek.SATURDAY
                "su" -> DayOfWeek.SUNDAY
                else -> throw java.lang.NumberFormatException("Invalid day code")
            }

            var (startHour, startMinute) = breakdownTimeCode(startTimeCode)
            var (endHour, endMinute) = if (endTimeCode == null)
                Pair((startHour + defaultDuration()) % 24, startMinute)
                else breakdownTimeCode(endTimeCode)

            //Log.i("ScheduleViewModel.matchToShift", "$startHour $startMinute $startPeriod $endHour $endMinute")

            if (startHour !in 0..24) {
                throw NumberFormatException("Start hour not in [0,24]")
            } else if (endHour !in 0..24) {
                throw NumberFormatException("End hour not in [0,24]")
            } else if (startMinute !in 0..59) {
                throw NumberFormatException("Start minute not in [0,59]")
            } else if (endMinute !in 0..59) {
                throw NumberFormatException("End minute not in [0,59]")
            } else if (startHour > 12 && startPeriod != null) {
                throw NumberFormatException("Invalid meridian code on 24h start time")
            } else if (endHour > 12 && endPeriod != null) {
                throw NumberFormatException("Invalid meridian code on 24h end time")
            }

            var start = LocalTime.of(startHour%24, startMinute, 0)
            var end = LocalTime.of(endHour%24, endMinute, 0)

            if ("pm".equals(startPeriod, true)) start = start.plusHours(12)
            if ("pm".equals(endPeriod, true)) end = end.plusHours(12)

            if (assumeDaytime()) {
                val dawn = LocalTime.of(7, 0)
                val noon = LocalTime.of(12, 0)

                if (startPeriod == null && start.isBefore(dawn)) start = start.plusHours(12)
                if (endPeriod == null
                    && end.isBefore(start)
                    && end.plusHours(12).isAfter(start)
                    && end.isBefore(noon)) end = end.plusHours(12)
            }

            flagger(match.range, true)
            return Shift(day, start, end)

        } catch (ex : NumberFormatException) {
            flagger(match.range, false)
            ex.printStackTrace()
            return null
        }
    }

    fun generatePreview() : Spanned {
        val buf = StringBuilder()
        buf.append("<h2>Review</h2>")

        for (shift in schedule.shifts) {
            val overnight = shift.startTime.dayOfMonth != shift.endTime.dayOfMonth
            buf.append("<h4>")
                .append(shift.startTime.format(DateTimeFormatter.ofPattern("EEEE")))
                .append(" ")
                .append(shift.startTime.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                .append(" to ")
                .append(shift.endTime.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                .append(" (")
                .append(shift.startTime.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                .append(")")
                .append("</h4>")
                .append(if (overnight) "(overnight)" else "")
        }

        return Html.fromHtml(buf.toString(), Html.FROM_HTML_MODE_LEGACY) ?: SpannedString("")
    }

}
