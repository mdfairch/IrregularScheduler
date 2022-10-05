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
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.CalendarListEntry
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import java.time.ZoneOffset
import java.util.*


class GoogleCalendarModel(
    private val context : Context,
    private val account : GoogleSignInAccount) {

    private val applicationName = "Irregular Scheduler"
    private val scopes = listOf(CalendarScopes.CALENDAR)
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private fun getSetting(code : Int, defaultVal : String) : String = prefs.getString(context.getString(code), defaultVal) ?: defaultVal
    private fun getOptional(code : Int) : String? = prefs.getString(context.getString(code), null)

    private val credential by lazy {
        GoogleAccountCredential.usingOAuth2(context, scopes)
            .setBackOff(ExponentialBackOff())
            .setSelectedAccount(account.account)
    }

    private val calendarService by lazy {
        Calendar
            .Builder(NetHttpTransport(), jsonFactory, credential)
            .setApplicationName(applicationName)
            .build()
    }

    fun getSelectedCalendar() : CalendarListEntry {
        return calendarService.calendarList().get(selectedCalendarId).execute()
    }

    val selectedCalendarId : String
        get() = getSetting(R.string.setting_selected_calendar, "primary")


    fun getCalendarList(): List<CalendarListEntry> {
        val mutableList = mutableListOf<CalendarListEntry>()
        var pageToken : String? = null
        do {
            val calendarList = calendarService.calendarList().list().setPageToken(pageToken).execute()
            pageToken = calendarList.nextPageToken
            val accessibleCalendars = calendarList.items
                .filter { cal -> !cal.isHidden && !cal.isDeleted}
                .filter{ cal -> cal.accessRole.equals("writer", true) || cal.accessRole.equals("owner", true)}
                .toList()
            mutableList.addAll(accessibleCalendars)
        } while(pageToken != null)
        return mutableList
    }

    fun createEvents(schedule : ScheduleViewModel.Schedule) : List<String> {
        val calendar = getSelectedCalendar()
        val timeZone = TimeZone.getTimeZone(calendar.timeZone) ?: TimeZone.getDefault()
        val zoneId = timeZone.toZoneId()

        val summary = getSetting(R.string.setting_event_name, "Work")
        val description = getOptional(R.string.setting_event_description)
        val address = getOptional(R.string.setting_address)
        val colorId = getSetting(R.string.setting_colour, "0")

        Log.i("GoogleApiViewModel.createEvents", "timeZone = $timeZone, default = ${TimeZone.getDefault()}")
        Log.i("GoogleApiViewModel.createEvents", "zoneID = $zoneId, default = ${ZoneOffset.systemDefault()}")
        Log.i("GoogleApiViewModel.createEvents", "name = $summary, desc = $description, address = $address, colorId = $colorId")

        val events = calendarService.events()

        return schedule.shifts
            .asSequence()
            .map { Event()
                .setSummary(summary)
                .setColorId(colorId)
                .setStart(EventDateTime().setDateTime(DateTime(it.getStartAsDate(zoneId), timeZone)))
                .setEnd(EventDateTime().setDateTime(DateTime(it.getEndAsDate(zoneId), timeZone)))
            }
            .map { if (description != null) it.setDescription(description) else it }
            .map { if (address != null) it.setLocation(address) else it }
            .map { events.insert(calendar.id, it).execute() }
            .map{ it.htmlLink }
            .toList()
    }
}
