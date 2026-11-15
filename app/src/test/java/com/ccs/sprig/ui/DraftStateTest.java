package com.ccs.sprig.ui;

import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.ccs.sprig.SprigApp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Large request drafts must survive rotation without entering Android's limited state parcel. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = SprigApp.class)
public class DraftStateTest {
    @Test public void largeRequestDraftStaysOutOfSavedStateAndSurvivesRecreation() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView();
            BottomNavigationView navigation = find(root, BottomNavigationView.class);
            assertNotNull(navigation);
            for (int i = 0; i < navigation.getMenu().size(); i++) {
                if ("Repeater".contentEquals(navigation.getMenu().getItem(i).getTitle())) {
                    navigation.setSelectedItemId(navigation.getMenu().getItem(i).getItemId());
                    break;
                }
            }
            EditText request = findRequest(root);
            assertNotNull("The raw request editor must be present", request);
            String prefix = "GET / HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
            StringBuilder draft = new StringBuilder(prefix);
            for (int i = 0; i < 900 * 1024; i++) draft.append('x');
            request.setText(draft.toString());

            Bundle savedState = new Bundle();
            controller.saveInstanceState(savedState);
            Parcel parcel = Parcel.obtain();
            try {
                savedState.writeToParcel(parcel, 0);
                assertTrue("Editor content must not exhaust Android's saved-state transaction", parcel.dataSize() < 64 * 1024);
            } finally {
                parcel.recycle();
            }

            controller.recreate();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            EditText restored = findRequest(controller.get().getWindow().getDecorView());
            assertNotNull("Repeater must be restored", restored);
            assertEquals("The complete process-local draft must survive activity recreation", draft.toString(), restored.getText().toString());

            // Keep subsequent screenshot tests independent of this large draft.
            restored.setText("GET / HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\nConnection: close\r\n\r\n");
            controller.saveInstanceState(new Bundle());
        }
    }

    private static EditText findRequest(View view) {
        if (view instanceof EditText && ((EditText) view).getText().toString().startsWith("GET / HTTP/1.1")) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText result = findRequest(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = find(group.getChildAt(i), type);
                if (result != null) return result;
            }
        }
        return null;
    }
}
