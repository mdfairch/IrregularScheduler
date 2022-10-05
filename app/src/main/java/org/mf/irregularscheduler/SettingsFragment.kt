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

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Html
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.viewModels
import androidx.preference.*
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.Preference.OnPreferenceClickListener
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.prefs.PreferenceChangeEvent
import java.util.prefs.PreferenceChangeListener


class SettingsFragment : PreferenceFragmentCompat()  {

    private val apiModel : GoogleApiViewModel by viewModels()
    private var calendarModelLoaded = false

    private fun getSetting(code : Int, defaultVal : String) : String =
        preferenceManager.sharedPreferences?.getString(context?.getString(code), defaultVal) ?: defaultVal

    private fun getSelectedCalendar() : String {
        return getSetting(R.string.setting_selected_calendar, "primary")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        val prefEventName = findPreference<EditTextPreference>(getString(R.string.setting_event_name))
        prefEventName?.setOnPreferenceChangeListener { _, newVal ->
            newVal.toString().length > 2
        }

        val prefChangeAccount = findPreference<Preference>(getString(R.string.setting_selected_account))
        prefChangeAccount?.onPreferenceClickListener = OnPreferenceClickListener {
            signIn()
            true
        }

        val prefDuration = findPreference<DropDownPreference>(getString(R.string.setting_default_duration))
        prefDuration?.setSummaryProvider {
            getString(R.string.setting_default_duration_summary, prefDuration.entry)
        }

        val prefColour = findPreference<DropDownPreference>(getString(R.string.setting_colour))
        prefColour?.setSummaryProvider {
            getString(R.string.settings_colour_summary, prefColour.entry)
        }

        val prefCalendar = findPreference<DropDownPreference>(getString(R.string.setting_selected_calendar))
        prefCalendar?.isEnabled = false
        prefCalendar?.onPreferenceChangeListener = OnPreferenceChangeListener { preference, newValue ->
                Log.i("$tag.calendarChange", "Old value = ${prefCalendar?.value}, new value = $newValue, loaded = $calendarModelLoaded")
                calendarModelLoaded
            }

        apiModel.tryLastSignIn()

        if (!apiModel.isValidAccount()) {
            Log.i("$tag.onCreatePreferences", "Account invalid, trying silent signIn.")
            apiModel.silentSignIn {
                Log.i("$tag.onCreatePreferences", "After silent signIn attempt, updating display.")
                updateFromApi()
            }
        } else {
            Log.i("$tag.onCreatePreferences", "Account looks good! Updating display.")
            updateFromApi()
        }
    }

    private fun signIn() {
        if (!apiModel.isValidAccount()) {
            Log.i("$tag.signIn", "Account invalid, launching signIn intent.")
            accountChooserContract.launch(apiModel.getSignInIntent())
        } else {
            AlertDialog.Builder(requireContext()).setMessage("Sign Out and choose a different account?")
                .setNegativeButton("Cancel") { _, _ -> }
                .setPositiveButton("Yes") { _, _ ->
                    Log.i("$tag.signIn", "Signing out and launching signIn intent.")
                    apiModel.signOutAnd { accountChooserContract.launch(apiModel.getSignInIntent()) }
                }
                .show()
        }
    }


    private val accountChooserContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            try {
                Log.i("$tag.accountChooserContract", "RESULT_OK")
                apiModel.handleSignIn(GoogleSignIn.getSignedInAccountFromIntent(it.data))
            } catch (e : ApiException) {
                Log.e("$tag.accountChooserContract", "$e.message")
                apiModel.showErrorDialog(e.statusCode, this)
            }
        } else if (it.resultCode == Activity.RESULT_CANCELED) {
            try {
                Log.i("$tag.accountChooserContract", "RESULT_CANCELED")
                apiModel.handleSignIn(GoogleSignIn.getSignedInAccountFromIntent(it.data))
            } catch (e : ApiException) {
                Log.e("$tag.accountChooserContract", "$e.message")
                apiModel.showErrorDialog(e.statusCode, this)
            }
        } else {
            Log.i("$tag.accountChooserContract", it.toString())
        }

        Log.i("$tag.accountChooserContract", "Updating display.")
        updateFromApi()
    }

    private fun updateFromApi() {
        if (apiModel.isValidAccount()) {
            findPreference<Preference>(getString(R.string.setting_selected_account)).let {
                it?.setSummaryProvider { apiModel.getAccountName() ?: getString(R.string.errorMessage_notSignedIn) }
            }
        } else {
            findPreference<Preference>(getString(R.string.setting_selected_account)).let {
                it?.setSummaryProvider { getString(R.string.errorMessage_notSignedIn) }
            }
        }

        reloadCalendars()
    }

    private fun reloadCalendars() {
        findPreference<DropDownPreference>(getString(R.string.setting_selected_calendar))?.isEnabled = false
        calendarModelLoaded = false

        Thread{
            try {
                val calendarModel = apiModel.getCalendarModel()
                if (calendarModel == null) {
                    clearCalendars()
                } else {
                    populateCalendars(calendarModel)
                }
            } catch (ex : UserRecoverableAuthIOException) {
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (it.resultCode == Activity.RESULT_OK) reloadCalendars()
                    else clearCalendars()
                }.launch(ex.intent)
            }
        }.start()
    }

    private fun clearCalendars() {
        val settingDefaultCalendar = getString(R.string.setting_selected_calendar)
        val prefDefaultCalendar = findPreference<DropDownPreference>(settingDefaultCalendar)

        requireActivity().runOnUiThread {
            prefDefaultCalendar?.entries = arrayOf("(default)")
            prefDefaultCalendar?.entryValues = arrayOf("primary")
            prefDefaultCalendar?.setDefaultValue("primary")
        }
    }

    private fun populateCalendars(calendarModel : GoogleCalendarModel) {
        val defaultCalendarDisplay = getString(R.string.defaultCalendarDisplay)
        val calendars = calendarModel.getCalendarList()
        val entries = (listOf(defaultCalendarDisplay) + calendars.map { it.summary }).toTypedArray()
        val entryValues = (listOf("primary") + calendars.map { it.id }).toTypedArray()

        val cal = getSelectedCalendar()
        val settingDefaultCalendar = getString(R.string.setting_selected_calendar)
        val prefDefaultCalendar =
            findPreference<DropDownPreference>(settingDefaultCalendar)

        requireActivity().runOnUiThread() {
            prefDefaultCalendar?.entries = entries
            prefDefaultCalendar?.entryValues = entryValues
            prefDefaultCalendar?.setDefaultValue("primary")

            Thread {
                runBlocking { delay(400) }
                requireActivity().runOnUiThread {
                    calendarModelLoaded = true
                    findPreference<DropDownPreference>(getString(R.string.setting_selected_calendar))?.isEnabled = true
                    prefDefaultCalendar?.value = cal
                }
            }.start()
        }
    }

}
