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

import android.accounts.AccountManager
import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.preference.PreferenceManager
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import org.mf.irregularscheduler.databinding.FragmentSubmitBinding
import android.Manifest.*
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.security.Permission


/**
 * A simple [Fragment] subclass.
 */
class SubmitFragment : Fragment() {

    private val scheduleModel: ScheduleViewModel by activityViewModels()
    private val apiModel : GoogleApiViewModel by viewModels()
    private var _binding: FragmentSubmitBinding? = null

    private val report: (String)->Unit = {
        Log.i("SubmitFragment.report", it)
        requireActivity().runOnUiThread { binding.textviewResultsOfSubmit.append("$it\n") }
    }
    private val reportStep: (String)->Unit = { report("✓ $it") }
    private val reportIssue: (String)->Unit = { report("✗ $it") }
    private val reportWork: (String)->Unit = { report("… $it") }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentSubmitBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startSubmission()
    }

    private fun startSubmission() {
        Thread {
            try {
                reportWork("Beginning schedule submissions...")

                if (!apiModel.isDeviceOnline()) throw NotConnectedException()
                reportStep("Internet connection detected")

                if (!apiModel.isServiceAvailable(this)) throw GMSUnavailable()
                reportStep("GooglePlay Services detected")

                for (permission in apiModel.neededPermissions) {
                    val result = ContextCompat.checkSelfPermission(requireContext(), permission)
                    if (result != PackageManager.PERMISSION_GRANTED) throw NeedPermissions(permission)
                    else reportStep("Found permission for $permission")
                }
                reportStep("Permissions checked.")

                if (!apiModel.isValidAccount()) throw InvalidCredential()
                reportStep("Signed into Google as ${apiModel.getAccountName()}")

                val calendarModel = apiModel.getCalendarModel() ?: throw CalendarUnavailable()
                reportStep("Accessed Google Calendar")

                val calId = calendarModel.selectedCalendarId
                if (calId == "primary") reportWork("Looking for primary calendar")
                else reportWork("Looking for calendar ${calId.take(15)}")

                val calendarEntry = calendarModel.getSelectedCalendar()
                if (calId != "primary" && calId != calendarEntry.id) throw IncorrectCalendar()
                reportStep("Accessing calendar ${calendarEntry.summary}")

                calendarModel.createEvents(scheduleModel.schedule)
                scheduleModel.clear()
                reportStep("No problems detected.")

                val linksText = Html.fromHtml("<h1>DONE</h1>", HtmlCompat.FROM_HTML_MODE_COMPACT)
                requireActivity().runOnUiThread { binding.textviewLinks.text = linksText }

                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                prefs.edit().putString(getString(R.string.setting_schedule_code), "").apply()

            } catch (ex : NotConnectedException) {
                reportIssue("No internet connection!")

            } catch (ex : GMSUnavailable) {
                reportIssue("GooglePlay services not detected!")

            } catch (ex : NeedPermissions) {
                reportWork("Acquiring permission: ${ex.permission}")
                getPermissionsContract.launch(apiModel.neededPermissions)

            } catch (ex : CalendarEntryUnavailable) {
                reportIssue("Couldn't load calendar!")

            } catch (ex : IncorrectCalendar) {
                reportIssue("Retrieved incorrect calendar!")

            } catch (ex : UserRecoverableAuthIOException) {
                reportWork("Trying to authorize.")
                getAuthContract.launch(ex.intent)

            } catch (ex: InvalidCredential) {
                reportWork("Trying get credential.")
                getCredentialContract.launch(apiModel.getSignInIntent())

            } catch (ex : Exception) {
                reportIssue("Something went wrong: ${ex.message}")
                ex.printStackTrace()
            }

        }.start()
    }

    private val getPermissionsContract = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.all { it.value }) startSubmission()
        else reportIssue("Couldn't acquire permissions!")
    }

    private val getAuthContract = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            startSubmission()
        }
        else reportIssue("Google API not authorized!")
    }

    private val getCredentialContract = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            apiModel.handleSignIn(GoogleSignIn.getSignedInAccountFromIntent(it.data))
            startSubmission()
        } else reportIssue("Invalid credential!")
    }

    private class NotConnectedException : Exception()
    private class GMSUnavailable : Exception()
    private class NeedPermissions(val permission : String) : Exception()
    private class InvalidCredential : Exception()
    private class CalendarUnavailable : Exception()
    private class CalendarEntryUnavailable : Exception()
    private class IncorrectCalendar : Exception()

}