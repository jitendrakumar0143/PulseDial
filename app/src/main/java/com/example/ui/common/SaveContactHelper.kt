package com.example.ui.common

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.ContactsRepository
import com.example.databinding.DialogSaveContactBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.launch

object SaveContactHelper {

    fun showSaveNumberDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        initialNumber: String = "",
        initialName: String = "",
        onSaved: () -> Unit = {}
    ) {
        val repository = ContactsRepository(context)
        val dialogBinding = DialogSaveContactBinding.inflate(LayoutInflater.from(context))
        dialogBinding.etSaveName.setText(initialName)
        dialogBinding.etSaveNumber.setText(initialNumber)

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

        if (repository.hasContactsPermission()) {
            lifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val allAccounts = repository.getGoogleAccountNames(includeRawContactsFallback = true)
                val extras = allAccounts.filter { candidate ->
                    googleAccounts.none { it.equals(candidate, ignoreCase = true) }
                }
                if (extras.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
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
                        }
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.save_contact_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save_contact_btn) { _, _ ->
                val name = dialogBinding.etSaveName.text?.toString().orEmpty().trim()
                val number = dialogBinding.etSaveNumber.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, R.string.save_contact_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (number.isEmpty()) return@setPositiveButton

                val checkedId = radioGroup.checkedRadioButtonId
                val selectedAccountName = if (checkedId != View.NO_ID) {
                    radioGroup.findViewById<View>(checkedId)?.tag as? String
                } else {
                    googleAccounts.firstOrNull()
                }

                if (repository.hasWriteContactsPermission()) {
                    lifecycleOwner.lifecycleScope.launch {
                        val saved = repository.saveNewContact(name, number, selectedAccountName)
                        if (saved) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.save_contact_success, name),
                                Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        } else {
                            openSystemInsertContact(context, name, number, selectedAccountName)
                        }
                    }
                } else {
                    openSystemInsertContact(context, name, number, selectedAccountName)
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openSystemInsertContact(
        context: Context,
        name: String,
        number: String,
        googleAccountName: String? = null
    ) {
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
        }
        runCatching { context.startActivity(intent) }
    }
}
