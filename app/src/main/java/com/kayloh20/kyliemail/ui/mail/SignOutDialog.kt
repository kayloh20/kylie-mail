package com.kayloh20.kyliemail.ui.mail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Signing out wipes everything kept on this device, so it's never one stray tap away. */
@Composable
fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = {
            Text(
                "This removes everything Kylie Mail keeps on this device: downloaded mail, unsent drafts, " +
                    "and what smart filing has learned, which would need training again. " +
                    "Your mail on iCloud isn't touched.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Sign out") }
        },
        // The safe choice is the easy one: Escape, tapping outside, and this all keep you signed in.
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay signed in") } },
    )
}
