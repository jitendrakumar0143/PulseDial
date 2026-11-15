package com.example.ui.common

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.ContactsRepository
import com.example.databinding.DialogSaveContactBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

                if (repository.hasWriteContactsPermission()) {
                    lifecycleOwner.lifecycleScope.launch {
                        val saved = repository.saveNewContact(name, number)
                        if (saved) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.save_contact_success, name),
                                Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        } else {
                            openSystemInsertContact(context, name, number)
                        }
                    }
                } else {
                    openSystemInsertContact(context, name, number)
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openSystemInsertContact(context: Context, name: String, number: String) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        }
        runCatching { context.startActivity(intent) }
    }
}
