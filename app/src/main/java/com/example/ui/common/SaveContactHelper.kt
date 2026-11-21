package com.example.ui.common

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.databinding.DialogSaveContactBinding
import com.example.ui.theme.ThemeStudioManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SaveContactHelper {

    fun showSaveNumberDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        initialNumber: String = "",
        initialName: String = "",
        isEditingExisting: Boolean = false,
        onSaved: () -> Unit = {}
    ) {
        val repository = ContactsRepository(context)
        val prefs = DialerPreferences(context)
        val dialogBinding = DialogSaveContactBinding.inflate(LayoutInflater.from(context))

        val cleanInitialName = initialName.trim().takeUnless {
            it.isEmpty() ||
                it.equals("Unknown", ignoreCase = true) ||
                it.equals("Unknown Caller", ignoreCase = true) ||
                ContactsRepository.isNameJustPhoneNumber(it, initialNumber)
        }.orEmpty()

        dialogBinding.etSaveName.setText(cleanInitialName)
        dialogBinding.etSaveNumber.setText(initialNumber.trim())

        dialogBinding.etSaveName.doAfterTextChanged {
            dialogBinding.tilSaveName.error = null
        }
        dialogBinding.etSaveNumber.doAfterTextChanged {
            dialogBinding.tilSaveNumber.error = null
        }

        val googleAccounts = repository.getGoogleAccountNames(includeRawContactsFallback = false).toMutableList()
        val radioGroup = dialogBinding.rgSaveAccount
        var defaultRadioId = View.NO_ID

        fun createGmailRadio(email: String): MaterialRadioButton {
            return MaterialRadioButton(context).apply {
                id = View.generateViewId()
                text = context.getString(R.string.save_contact_gmail_format, email)
                tag = email
                minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
            }
        }

        googleAccounts.forEach { email ->
            val rb = createGmailRadio(email)
            radioGroup.addView(rb)
            if (defaultRadioId == View.NO_ID) {
                defaultRadioId = rb.id
            }
        }

        val localRbId = View.generateViewId()
        val localRb = MaterialRadioButton(context).apply {
            id = localRbId
            setText(R.string.save_contact_phone_storage)
            tag = null
            minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
        }
        radioGroup.addView(localRb)
        if (defaultRadioId == View.NO_ID) {
            defaultRadioId = localRbId
        }
        radioGroup.check(defaultRadioId)

        if (ThemeStudioManager.isFullApp3DMixThemeActive(prefs)) {
            ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(
                rootView = dialogBinding.root,
                prefs = prefs,
                applyRootBackground = true
            )
        }

        if (repository.hasContactsPermission()) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val allAccounts = repository.getGoogleAccountNames(includeRawContactsFallback = true)
                val extras = allAccounts.filter { candidate ->
                    googleAccounts.none { it.equals(candidate, ignoreCase = true) }
                }
                if (extras.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (radioGroup.isAttachedToWindow) {
                            val hadNoGmail = googleAccounts.isEmpty()
                            extras.forEach { email ->
                                googleAccounts.add(email)
                                val rb = createGmailRadio(email)
                                val insertAt = (radioGroup.childCount - 1).coerceAtLeast(0)
                                radioGroup.addView(rb, insertAt)
                                if (hadNoGmail && radioGroup.checkedRadioButtonId == localRbId) {
                                    radioGroup.check(rb.id)
                                }
                            }
                            if (ThemeStudioManager.isFullApp3DMixThemeActive(prefs)) {
                                ThemeStudioManager.invalidateHierarchyThemeTag(dialogBinding.root)
                                ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(
                                    rootView = dialogBinding.root,
                                    prefs = prefs,
                                    applyRootBackground = true
                                )
                            }
                        }
                    }
                }
            }
        }

        val titleRes = if (isEditingExisting) R.string.save_contact_edit_title else R.string.save_contact_title
        val positiveRes = if (isEditingExisting) R.string.save_contact_update_btn else R.string.save_contact_btn

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(positiveRes, null)
            .setNegativeButton(R.string.dialog_close, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val name = dialogBinding.etSaveName.text?.toString().orEmpty().trim()
                val number = dialogBinding.etSaveNumber.text?.toString().orEmpty().trim()

                var hasError = false
                if (name.isEmpty()) {
                    dialogBinding.tilSaveName.error = context.getString(R.string.save_contact_name_required)
                    hasError = true
                }
                if (number.isEmpty()) {
                    dialogBinding.tilSaveNumber.error = context.getString(R.string.save_contact_number_required)
                    hasError = true
                }
                if (hasError) return@setOnClickListener

                val checkedId = radioGroup.checkedRadioButtonId
                val selectedAccountName = if (checkedId != View.NO_ID) {
                    radioGroup.findViewById<View>(checkedId)?.tag as? String
                } else {
                    googleAccounts.firstOrNull()
                }

                if (repository.hasWriteContactsPermission()) {
                    saveButton.isEnabled = false
                    lifecycleOwner.lifecycleScope.launch {
                        val existedBefore = repository.lookupContactByNumber(number) != null
                        val saved = repository.saveNewContact(name, number, selectedAccountName)
                        if (saved) {
                            val toastRes = if (existedBefore || isEditingExisting) {
                                R.string.save_contact_updated_success
                            } else {
                                R.string.save_contact_success
                            }
                            Toast.makeText(
                                context,
                                context.getString(toastRes, name),
                                Toast.LENGTH_SHORT
                            ).show()
                            runCatching { dialog.dismiss() }
                            onSaved()
                        } else {
                            saveButton.isEnabled = true
                            val launched = openSystemInsertContact(context, name, number, selectedAccountName)
                            if (launched) {
                                runCatching { dialog.dismiss() }
                            } else {
                                Toast.makeText(context, R.string.save_contact_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val launched = openSystemInsertContact(context, name, number, selectedAccountName)
                    if (launched) {
                        runCatching { dialog.dismiss() }
                    } else {
                        Toast.makeText(context, R.string.save_contact_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun openSystemInsertContact(
        context: Context,
        name: String,
        number: String,
        googleAccountName: String? = null
    ): Boolean {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            if (!googleAccountName.isNullOrBlank()) {
                putExtra(
                    ContactsContract.Intents.Insert.EXTRA_ACCOUNT,
                    Account(googleAccountName, ContactsRepository.GOOGLE_ACCOUNT_TYPE)
                )
            }
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
