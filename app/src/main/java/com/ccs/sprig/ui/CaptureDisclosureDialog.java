package com.ccs.sprig.ui;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.ccs.sprig.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Consent describes real forwarding separately from local capture storage. */
public final class CaptureDisclosureDialog extends DialogFragment {
    static final String TAG="capture-data-disclosure";
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.capture_disclosure_title)
                .setMessage(R.string.capture_disclosure_body)
                .setPositiveButton(R.string.capture_disclosure_accept, (dialog, which) ->
                        ((MainActivity)requireActivity()).requestCapturePermissions())
                .setNegativeButton(R.string.common_cancel, null).create();
    }
}
