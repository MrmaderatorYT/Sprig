package com.ccs.sprig.ui;

import androidx.annotation.StringRes;
import com.ccs.sprig.SprigApp;

/** Resources for shared worker/model diagnostics; never translates captured wire data. */
public final class UiText {
    private UiText() {}
    public static String get(@StringRes int resource) {
        return SprigApp.get().getString(resource);
    }
    public static String get(@StringRes int resource, Object... arguments) {
        return SprigApp.get().getString(resource, arguments);
    }
}
