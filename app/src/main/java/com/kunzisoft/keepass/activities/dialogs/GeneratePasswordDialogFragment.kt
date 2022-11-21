/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.activities.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import org.digicraft.keepass.R
import com.kunzisoft.keepass.database.element.Field
import com.kunzisoft.keepass.password.PasswordGenerator
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.timeout.ClipboardHelper
import com.kunzisoft.keepass.view.applyFontVisibility

class GeneratePasswordDialogFragment : DatabaseDialogFragment() {

    private var mListener: GeneratePasswordListener? = null

    private var root: View? = null
    private var lengthTextView: EditText? = null
    private var passwordInputLayoutView: TextInputLayout? = null
    private var passwordView: EditText? = null

    private var mPasswordField: Field? = null

    private var uppercaseBox: CompoundButton? = null
    private var lowercaseBox: CompoundButton? = null
    private var digitsBox: CompoundButton? = null
    private var minusBox: CompoundButton? = null
    private var underlineBox: CompoundButton? = null
    private var spaceBox: CompoundButton? = null
    private var specialsBox: CompoundButton? = null
    private var bracketsBox: CompoundButton? = null
    private var extendedBox: CompoundButton? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mListener = context as GeneratePasswordListener
        } catch (e: ClassCastException) {
            throw ClassCastException(context.toString()
                    + " must implement " + GeneratePasswordListener::class.java.name)
        }
    }

    override fun onDetach() {
        mListener = null
        super.onDetach()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        activity?.let { activity ->
            val builder = AlertDialog.Builder(activity)
            val inflater = activity.layoutInflater
            root = inflater.inflate(R.layout.fragment_generate_password, null)

            passwordInputLayoutView = root?.findViewById(R.id.password_input_layout)
            passwordView = root?.findViewById(R.id.password)
            passwordView?.applyFontVisibility()
            val passwordCopyView: ImageView? = root?.findViewById(R.id.password_copy_button)
            passwordCopyView?.visibility = if(PreferencesUtil.allowCopyProtectedFields(activity))
                View.VISIBLE else View.GONE
            val clipboardHelper = ClipboardHelper(activity)
            passwordCopyView?.setOnClickListener {
                clipboardHelper.timeoutCopyToClipboard(passwordView!!.text.toString(),
                        getString(R.string.copy_field,
                                getString(R.string.entry_password)))
            }

            lengthTextView = root?.findViewById(R.id.length)

            uppercaseBox = root?.findViewById(R.id.cb_uppercase)
            lowercaseBox = root?.findViewById(R.id.cb_lowercase)
            digitsBox = root?.findViewById(R.id.cb_digits)
            minusBox = root?.findViewById(R.id.cb_minus)
            underlineBox = root?.findViewById(R.id.cb_underline)
            spaceBox = root?.findViewById(R.id.cb_space)
            specialsBox = root?.findViewById(R.id.cb_specials)
            bracketsBox = root?.findViewById(R.id.cb_brackets)
            extendedBox = root?.findViewById(R.id.cb_extended)

            mPasswordField = arguments?.getParcelable(KEY_PASSWORD_FIELD)

            assignDefaultCharacters()

            val seekBar = root?.findViewById<SeekBar>(R.id.seekbar_length)
            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    lengthTextView?.setText(progress.toString())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })

            context?.let { context ->
                seekBar?.progress = PreferencesUtil.getDefaultPasswordLength(context)
            }

            root?.findViewById<Button>(R.id.generate_password_button)
                    ?.setOnClickListener { fillPassword() }

            builder.setView(root)
                    .setPositiveButton(R.string.accept) { _, _ ->
                        mPasswordField?.let { passwordField ->
                            passwordView?.text?.toString()?.let { passwordValue ->
                                passwordField.protectedValue.stringValue = passwordValue
                            }
                            mListener?.acceptPassword(passwordField)
                        }
                        dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        mPasswordField?.let { passwordField ->
                            mListener?.cancelPassword(passwordField)
                        }
                        dismiss()
                    }

            // Pre-populate a password to possibly save the user a few clicks
            fillPassword()

            return builder.create()
        }
        return super.onCreateDialog(savedInstanceState)
    }

    private fun assignDefaultCharacters() {
        uppercaseBox?.isChecked = false
        lowercaseBox?.isChecked = false
        digitsBox?.isChecked = false
        minusBox?.isChecked = false
        underlineBox?.isChecked = false
        spaceBox?.isChecked = false
        specialsBox?.isChecked = false
        bracketsBox?.isChecked = false
        extendedBox?.isChecked = false

        context?.let { context ->
            PreferencesUtil.getDefaultPasswordCharacters(context)?.let { charSet ->
                for (passwordChar in charSet) {
                    when (passwordChar) {
                        getString(R.string.value_password_uppercase) -> uppercaseBox?.isChecked = true
                        getString(R.string.value_password_lowercase) -> lowercaseBox?.isChecked = true
                        getString(R.string.value_password_digits) -> digitsBox?.isChecked = true
                        getString(R.string.value_password_minus) -> minusBox?.isChecked = true
                        getString(R.string.value_password_underline) -> underlineBox?.isChecked = true
                        getString(R.string.value_password_space) -> spaceBox?.isChecked = true
                        getString(R.string.value_password_special) -> specialsBox?.isChecked = true
                        getString(R.string.value_password_brackets) -> bracketsBox?.isChecked = true
                        getString(R.string.value_password_extended) -> extendedBox?.isChecked = true
                    }
                }
            }
        }
    }

    private fun fillPassword() {
        root?.findViewById<EditText>(R.id.password)?.setText(generatePassword())
    }

    fun generatePassword(): String {
        var password = ""
        try {
            val length = Integer.valueOf(root?.findViewById<EditText>(R.id.length)?.text.toString())
            password = PasswordGenerator(resources).generatePassword(length,
                    uppercaseBox?.isChecked == true,
                    lowercaseBox?.isChecked == true,
                    digitsBox?.isChecked == true,
                    minusBox?.isChecked == true,
                    underlineBox?.isChecked == true,
                    spaceBox?.isChecked == true,
                    specialsBox?.isChecked == true,
                    bracketsBox?.isChecked == true,
                    extendedBox?.isChecked == true)
            passwordInputLayoutView?.error = null
        } catch (e: NumberFormatException) {
            passwordInputLayoutView?.error = getString(R.string.error_wrong_length)
        } catch (e: IllegalArgumentException) {
            passwordInputLayoutView?.error = e.message
        }

        return password
    }

    interface GeneratePasswordListener {
        fun acceptPassword(passwordField: Field)
        fun cancelPassword(passwordField: Field)
    }

    companion object {
        private const val KEY_PASSWORD_FIELD = "KEY_PASSWORD_FIELD"

        fun getInstance(field: Field): GeneratePasswordDialogFragment {
            return GeneratePasswordDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(KEY_PASSWORD_FIELD, field)
                }
            }
        }
    }
}
