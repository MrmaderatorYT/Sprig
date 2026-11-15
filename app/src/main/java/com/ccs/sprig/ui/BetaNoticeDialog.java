package com.ccs.sprig.ui;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.ccs.sprig.R;
import com.ccs.sprig.SprigApp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Persists acknowledgement only after the user confirms; rotation retains the dialog. */
public final class BetaNoticeDialog extends DialogFragment {
    static final String TAG = "open-beta-notice";
    static final String ACKNOWLEDGED = "open_beta_acknowledged";
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        setCancelable(false);
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.beta_title).setMessage(R.string.beta_message)
                .setPositiveButton(R.string.beta_acknowledge, (dialog, which) ->
                        SprigApp.get().getPreferences().edit().putBoolean(ACKNOWLEDGED, true).apply())
                .setNeutralButton(R.string.send_feedback, null).create();
    }
    @Override public void onStart() {
        super.onStart();
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> AppInformation.sendFeedback(requireContext()));
    }
}
