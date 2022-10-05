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

import android.Manifest
import android.accounts.AccountManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Patterns
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import java.util.*


class GoogleApiViewModel(application: Application) : AndroidViewModel(application) {

    val neededPermissions = arrayOf(
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.INTERNET
    )

    private val tag = object{}.javaClass.name
    //private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(application) }
    private val googleApiAvailability by lazy { GoogleApiAvailability.getInstance() }
    private val requestGooglePlayServices = 1002

    private val googleSignInOptions by lazy { GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .build() }

    private val googleSignIn by lazy { GoogleSignIn.getClient(application, googleSignInOptions) }
    fun silentSignIn(after : () -> Unit) {
        googleSignIn.silentSignIn().addOnCompleteListener {
            try {
                handleSignIn(it)
            } catch (e : Throwable) {
                Log.e(tag, "Silent sign-in error {${e.message}")
            }
            after()
        }
    }

    private fun getLastSignIn() : GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(getApplication<Application>())

    /*fun tryBruteForce() : GoogleSignInAccount? {
        val accounts = getAccounts()
        return accounts.firstOrNull()?.let {
            googleSignIn.
            null
        }
    }*/

    fun tryLastSignIn() : GoogleSignInAccount? {
        Log.i("$tag.tryLastSignIn", "Attempting to get previous sign-in")
        googleAccount = GoogleSignIn.getLastSignedInAccount(getApplication<Application>())
        Log.i("$tag.tryLastSignIn", if (isValidAccount()) "Success!" else "Failure.")
        return googleAccount
    }

    fun signOutAnd(next : () -> Unit) {
        Log.i("$tag.signOutAnd", "Signing out...")
        googleSignIn.signOut().continueWith {
            Log.i("$tag.signOutAnd", "Signed out.")
            next()
        }
    }

    fun getSignInIntent() = googleSignIn.signInIntent
    private var googleAccount : GoogleSignInAccount? = getLastSignIn()
    fun getAccountName() : String? = googleAccount?.email

    fun isValidAccount() : Boolean {
        //return with(googleAccount) { (this != null) && !this.isExpired && with(this.email) { (this != null) && this.isNotBlank() } }
        if (googleAccount == null) {
            Log.w("$tag.isValidAccount", "Account is null.")
            return false
        } else if (googleAccount?.email == null) {
            Log.w("$tag.isValidAccount", "Email field missing.")
            return false
        } else if (googleAccount?.id == null) {
            Log.w("$tag.isValidAccount", "Email field missing.")
            return false
        } else if (googleAccount?.email?.isBlank() == true) {
            Log.w("$tag.isValidAccount", "Email field blank.")
            return false
        } else if (googleAccount?.isExpired == true) {
            Log.w("$tag.isValidAccount", "Account expired.")
            return true
        } else {
            return true
        }
    }

    @Throws(ApiException::class)
    fun handleSignIn(signInTask : Task<GoogleSignInAccount>) {
        try {
            Log.i("$tag.handleSignIn", "Trying to extract account.")
            googleAccount = signInTask.getResult(ApiException::class.java)
            Log.i("$tag.handleSignIn", "Signed into ${googleAccount?.id}")
        } catch (e : ApiException) {
            googleAccount = null
            Log.e("$tag.handleSignIn", "Sign-in failed: ${e.message}")
            throw e
        }
    }

    private fun getAccounts() : List<String> {
        val gmailPattern = Patterns.EMAIL_ADDRESS
        val accounts = AccountManager.get(getApplication<Application>().applicationContext).accounts
        Log.i(tag, accounts.contentToString())
        return accounts
            .map { it.name }
            .filter { gmailPattern.matcher(it).matches() }
            .toList()
    }

    fun getCalendarModel() : GoogleCalendarModel? {
        with(googleAccount) {
            if (this != null) return GoogleCalendarModel(getApplication(), this)
            else return null
        }
    }

    /**
    * Check that Google Play Services are available.
    */
    fun isServiceAvailable(frag : Fragment): Boolean {
        val connectionStatusCode = googleApiAvailability.isGooglePlayServicesAvailable(getApplication())

        when {
            googleApiAvailability.isUserResolvableError(connectionStatusCode) -> {
                showErrorDialog(connectionStatusCode, frag)
                Log.i("$tag.isServiceAvailable", "statusCode = $connectionStatusCode (User recoverable error)")
                return true
            }

            connectionStatusCode != ConnectionResult.SUCCESS -> {
                showErrorDialog(connectionStatusCode, frag)
                Log.i("$tag.isServiceAvailable", "statusCode = $connectionStatusCode (NOT user recoverable error)")
                return false
            }

            else -> {
                Log.i("$tag.isServiceAvailable","statusCode $connectionStatusCode (all good)")
                return true
            }
        }
    }

    /**
     * Displays an error message supplied by the Google API for requesting Google Play Services.
     */
    fun showErrorDialog(code : Int, frag : Fragment) {
        googleApiAvailability.getErrorDialog(frag, code, requestGooglePlayServices)?.show()
    }

    /**
     * Checks whether the device currently has a network connection.
     */
    fun isDeviceOnline(): Boolean {
        val connMgr = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        val capabilities = connMgr?.getNetworkCapabilities(connMgr.activeNetwork)

        return when {
            capabilities == null -> {
                Log.i("$tag.isDeviceOnline", "Couldn't retrieve network capabilities.")
                false
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                Log.i("$tag.isDeviceOnline", "NetworkCapabilities.TRANSPORT_CELLULAR")
                true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                Log.i("$tag.isDeviceOnline", "NetworkCapabilities.TRANSPORT_WIFI")
                true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                Log.i("$tag.isDeviceOnline", "NetworkCapabilities.TRANSPORT_ETHERNET")
                true
            }
            else -> {
                Log.i("$tag.isDeviceOnline", "NetworkCapabilities.TRANSPORT_ETHERNET")
                false
            }
        }
    }
}
