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
import android.app.Application
import android.graphics.Typeface
import android.os.Bundle
import android.text.*
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.mf.irregularscheduler.databinding.FragmentEntryBinding


/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class EntryFragment : Fragment() {

    private val scheduleModel : ScheduleViewModel by activityViewModels()
    private var _binding: FragmentEntryBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.edittextScheduleCode.doAfterTextChanged {  }
        binding.edittextScheduleCode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                checkScheduleCode()
            }
        }

        binding.edittextScheduleCode.doAfterTextChangedDebounce(1000) {
            checkScheduleCode()
        }

        binding.sliderDateOffset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                checkScheduleCode()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                checkScheduleCode()
            }
        }

        binding.buttonGenerate.setOnClickListener {
            val openSubmit : () -> Unit = { findNavController().navigate(R.id.action_EntryFragment_to_SubmitFragment) }

            if (scheduleModel.schedule.shifts.isNotEmpty())
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm_title)
                    .setMessage(R.string.confirm_message)
                    .setPositiveButton(R.string.confirm_yes) { _, _ -> openSubmit() }
                    .setNegativeButton(R.string.confirm_no, null)
                    .show()
        }

        binding.edittextScheduleCode.isSingleLine = true
        binding.edittextScheduleCode.setHorizontallyScrolling(false)
        binding.edittextScheduleCode.maxLines = 6
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).let{
            val storedText = it.getString(getString(R.string.setting_schedule_code), "") ?: ""
            binding.edittextScheduleCode.setText(storedText.take(200))
        }
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).let {
            val storedText = binding.edittextScheduleCode.text.toString()
            it.edit().putString(getString(R.string.setting_schedule_code), storedText).apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun checkScheduleCode() {
        clearValidityStyles()

        val offsetSelection = binding.sliderDateOffset.selectedItemPosition
        val offsetValues = resources.getIntArray(R.array.date_offset_values)
        val offset = offsetValues.getOrElse(offsetSelection) { 0 }
        scheduleModel.parseText(binding.edittextScheduleCode.text.toString(), offset, ::addValidityStyleToRange)

        binding.buttonGenerate.isEnabled = !scheduleModel.foundErrors && scheduleModel.schedule.shifts.isNotEmpty()
        binding.textviewScheduleOutput.text = scheduleModel.generatePreview()

        if (scheduleModel.foundErrors) {
            Log.i("ScheduleViewModel.textToSchedule", "There were matching errors.")
            binding.edittextScheduleCode.error = getString(R.string.msg_schedule_error)
        } else {
            binding.edittextScheduleCode.error = null
        }
    }

    /**
     * Clears the styles used for valid and invalid regions of edittextScheduleCode .
     */
    private fun clearValidityStyles() {
        val text = binding.edittextScheduleCode.text
        text.getSpans(0, text.length, UnderlineSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, StyleSpan::class.java).forEach { text.removeSpan(it) }
    }

    /**
     * Applies the valid or invalid style to a range of edittextScheduleCode.
     * Passed as a callback to the ViewModel that validates the input.
     */
    private fun addValidityStyleToRange(range : IntRange, isValid : Boolean) {
        if (range.step > 0) {
            val text = binding.edittextScheduleCode.text
            val style = if (isValid) StyleSpan(Typeface.BOLD_ITALIC) else UnderlineSpan()
            text.setSpan(style, range.first, range.last+1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun EditText.doAfterTextChangedDebounce(delayMillis : Long, input : (String) -> Unit) {
        var lastInput = ""
        var debounceJob: Job? = null
        val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        this.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(editable: Editable?) {
                if (editable != null) {
                    val newInput = editable.toString()
                    debounceJob?.cancel()
                    if (lastInput != newInput) {
                        lastInput = newInput
                        debounceJob = uiScope.launch {
                            delay(delayMillis)
                            if (lastInput == newInput) {
                                input(newInput)
                            }
                        }
                    }
                }
            }

            override fun beforeTextChanged(cs: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(cs: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

}