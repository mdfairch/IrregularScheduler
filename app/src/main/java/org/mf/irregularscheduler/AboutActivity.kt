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

import android.net.http.SslError
import android.os.Bundle
import android.text.Html
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.mf.irregularscheduler.databinding.ActivityAboutBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class AboutActivity : AppCompatActivity() {

    private var _binding: ActivityAboutBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()

        resources.openRawResource(R.raw.about).use {
            val s = BufferedReader(InputStreamReader(it)).readText()
            binding.textviewAbout.text = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
        }

        binding.textviewPrivacy.text = getString(R.string.message_loadingPrivacy)

        binding.webviewPrivacy.loadUrl("https://www.termsfeed.com/live/df7d58ca-a97b-4f3b-83e0-32d0ecfc1a12")
        binding.webviewPrivacy.settings.safeBrowsingEnabled = true
        binding.webviewPrivacy.settings.javaScriptEnabled = false
        binding.webviewPrivacy.webViewClient = object : WebViewClient() {
            override fun onPageFinished(tview : WebView?, url : String?) {
                if (binding.textviewPrivacy.text.length < 50) {
                    binding.textviewPrivacy.text = ""
                }
            }

            override fun shouldOverrideUrlLoading(view : WebView?, request : WebResourceRequest?) : Boolean {
                return true
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                displayBackupLicense()
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                super.onReceivedSslError(view, handler, error)
                displayBackupLicense()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                displayBackupLicense()
            }
        }

    }

    private fun displayBackupLicense() {
        binding.webviewPrivacy.isEnabled = false
        binding.webviewPrivacy.isVisible = false

        resources.openRawResource(R.raw.privacy_policy).use {
            val s = BufferedReader(InputStreamReader(it)).readText()
            binding.textviewPrivacy.text = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
        }
    }

}