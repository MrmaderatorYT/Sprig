package com.ccs.sprig.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.ccs.sprig.SprigApp;
import com.ccs.sprig.R;
import com.ccs.sprig.core.InterceptController;
import com.ccs.sprig.core.PassiveAnalyzer;
import com.ccs.sprig.core.PayloadRunner;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.proxy.CertificateAuthority;
import com.ccs.sprig.vpn.CaptureVpnService;

import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native, Java-only Material 3 workspace. Network work always stays off the UI thread. */
public final class MainActivity extends AppCompatActivity {
    private static final int BACKGROUND = Color.rgb(16, 20, 17);
    private static final int SURFACE = Color.rgb(25, 31, 27);
    private static final int SURFACE_HIGH = Color.rgb(34, 42, 36);
    private static final int OUTLINE = Color.rgb(58, 69, 61);
    private static final int PRIMARY = Color.rgb(182, 243, 198);
    private static final int PRIMARY_DARK = Color.rgb(23, 53, 33);
    private static final int TEXT = Color.rgb(229, 236, 230);
    private static final int MUTED = Color.rgb(157, 175, 161);
    private static final int ERROR = Color.rgb(255, 180, 171);
    private static final int AMBER = Color.rgb(238, 207, 151);
    private static final int TRAFFIC = 101, INTERCEPT = 102, REPEATER = 103, TOOLS = 104, SETTINGS_TAB = 105;
    private static final int VPN_PERMISSION = 701, EXPORT_HAR = 702, EXPORT_CA = 703, NOTIFICATIONS = 704;
    private static final int EDIT_LIMIT = 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = UiWorkQueue.create("sprig-ui-work",4);
    private final ExecutorService payloadWork = UiWorkQueue.create("sprig-payload-work",1);
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private final Runnable storeListener = () -> {
        if (refreshScheduled.compareAndSet(false, true)) main.postDelayed(() -> {
            refreshScheduled.set(false); if (this.resumed) refreshTraffic();
        }, 150);
    };
    private final Runnable interceptListener = () -> main.post(this::refreshIntercept);
    private final Runnable statusTicker = new Runnable() {
        @Override public void run() { refreshCaptureState(); main.postDelayed(this, 1000); }
    };
    private FrameLayout content;
    private BottomNavigationView navigation;
    private TextView topStatus;
    private int page = TRAFFIC;
    private boolean resumed;
    private TextView captureTitle, captureSubtitle, requestCount, transferCount, trafficCount;
    private MaterialButton captureButton;
    private TrafficAdapter trafficAdapter;
    private View trafficEmpty;
    private String query = "", trafficFilter = "All";
    private TextView interceptCount;
    private LinearLayout pendingContainer;
    private String repeaterUrl = "https://example.com/";
    private String repeaterRaw = "GET / HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\nConnection: close\r\n\r\n";
    private TextInputEditText urlEditor, requestEditor;
    private Spinner protocolEditor;
    private String repeaterProtocol="AUTO";
    private TextView replayResponse, replayMeta;
    private MaterialButton replayButton;
    private boolean replayBusy;
    private String lastReplayResponse = "Send a request to inspect the response here.";
    private String lastReplayMeta = "RESPONSE";
    private String decoderInput = "", comparerLeft = "", comparerRight = "";
    private int decoderMode;
    private PayloadRunner payloadRunner;
    /** Draft text remains process-local; Android saved-state parcels stay small. */
    private static final EditorDraft DRAFT = new EditorDraft();
    private static final java.util.concurrent.atomic.AtomicReference<ReplayHandoff> REPLAY_HANDOFF=new java.util.concurrent.atomic.AtomicReference<>();
    private static final class ReplayHandoff {
        final String url,protocol;final byte[] raw;
        ReplayHandoff(String url,byte[] raw,String protocol){this.url=url;this.raw=raw.clone();this.protocol=protocol;}
    }

    /** Tool-to-tool transfer stays in-process; Android intents never carry captured credentials or bodies. */
    public static void openRepeater(Context context,String url,byte[] raw,String protocol) {
        if(raw==null||raw.length>EDIT_LIMIT)throw new IllegalArgumentException("Repeater editor supports complete requests up to 1 MiB");
        REPLAY_HANDOFF.set(new ReplayHandoff(url,raw,protocol));
        context.startActivity(new Intent(context,MainActivity.class).putExtra("open_repeater",true)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private boolean consumeReplayHandoff(Intent intent) {
        if(intent==null||!intent.getBooleanExtra("open_repeater",false))return false;
        ReplayHandoff handoff=REPLAY_HANDOFF.getAndSet(null);if(handoff==null)return false;
        urlEditor=requestEditor=null;protocolEditor=null;
        repeaterUrl=handoff.url;repeaterRaw=new String(handoff.raw,StandardCharsets.ISO_8859_1);
        repeaterProtocol=handoff.protocol==null?"AUTO":handoff.protocol;page=REPEATER;return true;
    }

    private SprigApp app() { return SprigApp.get(); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (state != null) {
            page = state.getInt("page", TRAFFIC);
            query = state.getString("query", "");
            trafficFilter = state.getString("filter", "All");
        }
        if (DRAFT.initialized) {
            repeaterUrl = DRAFT.url; repeaterRaw = DRAFT.request; decoderInput = DRAFT.decoder;
            comparerLeft = DRAFT.left; comparerRight = DRAFT.right; decoderMode = DRAFT.decoderMode;
            repeaterProtocol = DRAFT.protocol == null ? "AUTO" : DRAFT.protocol;
        }
        consumeReplayHandoff(getIntent());
        LinearLayout root = column(); root.setBackgroundColor(BACKGROUND); root.setFocusableInTouchMode(true);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(76)));
        content = new FrameLayout(this); content.setClipChildren(true); content.setClipToPadding(true);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new BottomNavigationView(this);
        navigation.setBackgroundColor(SURFACE);
        navigation.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
        navigation.setItemIconTintList(navColors()); navigation.setItemTextColor(navColors());
        navigation.setItemActiveIndicatorColor(ColorStateList.valueOf(PRIMARY_DARK));
        navigation.setItemIconSize(dp(22));
        navigation.getMenu().add(0, TRAFFIC, 0, getString(R.string.nav_traffic)).setIcon(icon(GlyphDrawable.Kind.TRAFFIC, TEXT));
        navigation.getMenu().add(0, INTERCEPT, 1, getString(R.string.nav_intercept)).setIcon(icon(GlyphDrawable.Kind.PAUSE, TEXT));
        navigation.getMenu().add(0, REPEATER, 2, getString(R.string.nav_repeater)).setIcon(icon(GlyphDrawable.Kind.SEND, TEXT));
        navigation.getMenu().add(0, TOOLS, 3, getString(R.string.nav_tools)).setIcon(icon(GlyphDrawable.Kind.TOOLS, TEXT));
        navigation.getMenu().add(0, SETTINGS_TAB, 4, getString(R.string.nav_settings)).setIcon(icon(GlyphDrawable.Kind.SETTINGS, TEXT));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            navigation.setPadding(0, 0, 0, bars.bottom);
            boolean typing = keyboard.bottom > bars.bottom;
            navigation.setVisibility(typing ? View.GONE : View.VISIBLE);
            content.setPadding(0, 0, 0, typing ? keyboard.bottom : 0);
            return insets;
        });
        setContentView(root);
        root.requestFocus();
        navigation.setOnItemSelectedListener(item -> { showPage(item.getItemId()); return true; });
        navigation.setSelectedItemId(page);
        showPage(page);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if(consumeReplayHandoff(intent)){navigation.setSelectedItemId(REPEATER);showPage(REPEATER);}
    }

    @Override protected void onStart() {
        super.onStart(); resumed = true;
        app().getStore().addListener(storeListener);
        app().getInterceptor().addListener(interceptListener);
        main.post(statusTicker);
        refreshTraffic(); refreshIntercept();
    }

    @Override protected void onStop() {
        retainDraft();
        resumed = false; main.removeCallbacks(statusTicker);
        app().getStore().removeListener(storeListener);
        app().getInterceptor().removeListener(interceptListener);
        super.onStop();
    }

    @Override protected void onDestroy() { if (payloadRunner != null) payloadRunner.cancel(); main.removeCallbacksAndMessages(null); work.shutdownNow(); payloadWork.shutdownNow(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        retainDraft();
        out.putInt("page", page); out.putString("query", query.substring(0, Math.min(query.length(), 200))); out.putString("filter", trafficFilter);
        super.onSaveInstanceState(out);
    }

    private View buildHeader() {
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(22), dp(8), dp(22), dp(8));
        TextView mark = text("", 20, PRIMARY); mark.setCompoundDrawablesWithIntrinsicBounds(icon(GlyphDrawable.Kind.SHIELD, PRIMARY), null, null, null);
        mark.setGravity(Gravity.CENTER); mark.setPadding(dp(10), dp(10), dp(10), dp(10)); mark.setBackground(round(PRIMARY_DARK, 15));
        header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout wordmark = column(); LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(0, -2, 1); words.leftMargin = dp(12); header.addView(wordmark, words);
        TextView title = text("sprig", 27, TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setLetterSpacing(-0.025f); wordmark.addView(title);
        TextView caption = text("NETWORK WORKSPACE", 9, MUTED); caption.setLetterSpacing(0.17f); wordmark.addView(caption);
        topStatus = label(getString(R.string.capture_offline), MUTED); topStatus.setPadding(dp(10), dp(6), dp(10), dp(6)); topStatus.setBackground(round(SURFACE_HIGH, 30));
        header.addView(topStatus); return header;
    }

    private void showPage(int next) {
        captureRepeater(); page = next;
        captureTitle = captureSubtitle = requestCount = transferCount = trafficCount = null;
        trafficAdapter = null; trafficEmpty = null; captureButton = null; pendingContainer = null; interceptCount = null;
        urlEditor = requestEditor = null; protocolEditor = null; replayResponse = replayMeta = null; replayButton = null;
        content.removeAllViews();
        switch (next) {
            case INTERCEPT: content.addView(buildIntercept()); break;
            case REPEATER: content.addView(buildRepeater()); break;
            case TOOLS: content.addView(buildTools()); break;
            case SETTINGS_TAB: content.addView(buildSettings()); break;
            default: content.addView(buildTraffic());
        }
        if (content.getChildCount() != 0) content.getChildAt(0).setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        content.setAlpha(0.35f); content.animate().alpha(1f).setDuration(150).start();
        refreshCaptureState(); refreshTraffic(); refreshIntercept();
    }

    private View buildTraffic() {
        LinearLayout body = column(); body.setPadding(dp(20), dp(2), dp(20), 0);
        MaterialCardView capture = card(PRIMARY_DARK, 24);
        LinearLayout box = column(); box.setPadding(dp(18), dp(17), dp(18), dp(15));
        LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column(); captureTitle = text(getString(R.string.capture_ready), 20, PRIMARY); captureTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); labels.addView(captureTitle);
        captureSubtitle = text(getString(R.string.capture_no_root), 12, Color.rgb(171, 203, 179)); addMargin(labels, captureSubtitle, 5);
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shield = text("", 24, PRIMARY); shield.setCompoundDrawablesWithIntrinsicBounds(icon(GlyphDrawable.Kind.SHIELD, PRIMARY), null, null, null); top.addView(shield);
        box.addView(top);
        captureButton = button(getString(R.string.capture_start), GlyphDrawable.Kind.PLAY, true); captureButton.setOnClickListener(v -> toggleCapture());
        addMargin(box, captureButton, 12); capture.addView(box); body.addView(capture);

        LinearLayout metrics = row(); metrics.setPadding(dp(3), dp(15), dp(3), dp(12));
        requestCount = text("0", 19, TEXT); transferCount = text("0 B", 19, TEXT);
        metrics.addView(metric(requestCount, "REQUESTS"), new LinearLayout.LayoutParams(0, -2, 1));
        View divider = new View(this); divider.setBackgroundColor(OUTLINE); LinearLayout.LayoutParams div = new LinearLayout.LayoutParams(dp(1), dp(30)); div.leftMargin = dp(14); div.rightMargin = dp(22); metrics.addView(divider, div);
        metrics.addView(metric(transferCount, "TRANSFERRED"), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(metrics);

        LinearLayout title = row(); title.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("HTTP history", 19, TEXT); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        trafficCount = label("0", MUTED); title.addView(trafficCount);
        MaterialButton export = iconButton(GlyphDrawable.Kind.EXPORT, "Export HAR"); export.setOnClickListener(v -> exportHar()); title.addView(export, new LinearLayout.LayoutParams(dp(42), dp(42)));
        MaterialButton clear = iconButton(GlyphDrawable.Kind.TRASH, "Clear history"); clear.setOnClickListener(v -> confirmClear()); title.addView(clear, new LinearLayout.LayoutParams(dp(42), dp(42)));
        body.addView(title);
        TextInputLayout search = field("Search host, path, method, status", false, 1);
        search.setHintEnabled(false); search.getEditText().setHint("Search host, path, method, status"); search.getEditText().setTextSize(13);
        search.setStartIconDrawable(icon(GlyphDrawable.Kind.SEARCH, MUTED)); search.setStartIconTintList(ColorStateList.valueOf(MUTED));
        search.getEditText().setText(query); watch(search.getEditText(), value -> { query = value; refreshTraffic(); });
        body.addView(search, new LinearLayout.LayoutParams(-1, dp(53)));

        HorizontalScrollView filters = new HorizontalScrollView(this); filters.setHorizontalScrollBarEnabled(false);
        ChipGroup group = new ChipGroup(this); group.setSingleSelection(true); group.setSelectionRequired(true);
        for (String name : new String[]{"All", "HTTPS", "Errors"}) {
            Chip chip = chip(name); chip.setChecked(name.equals(trafficFilter)); group.addView(chip);
            chip.setOnCheckedChangeListener((b, checked) -> { if (checked) { trafficFilter = name; refreshTraffic(); } });
        }
        filters.addView(group); LinearLayout.LayoutParams f = new LinearLayout.LayoutParams(-1, dp(45)); f.topMargin = dp(2); body.addView(filters, f);
        FrameLayout history = new FrameLayout(this);
        RecyclerView list = new RecyclerView(this); list.setLayoutManager(new LinearLayoutManager(this)); list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(12));
        trafficAdapter = new TrafficAdapter(); list.setAdapter(trafficAdapter); list.setItemAnimator(null); history.addView(list, new FrameLayout.LayoutParams(-1, -1));
        trafficEmpty = empty(GlyphDrawable.Kind.TRAFFIC, "A clear view of every request", "Start a capture, then open a browser or an app.\nHTTP requests will appear here.");
        history.addView(trafficEmpty, new FrameLayout.LayoutParams(-1, -1)); body.addView(history, new LinearLayout.LayoutParams(-1, 0, 1));
        return body;
    }

    private LinearLayout metric(TextView value, String title) {
        LinearLayout metric = column(); metric.addView(value); TextView description = label(title, MUTED); description.setTextSize(9); description.setLetterSpacing(0.12f); addMargin(metric, description, 3); return metric;
    }

    private void refreshTraffic() {
        if (isDestroyed()) return;
        if (trafficAdapter == null && requestCount == null) return;
        List<Exchange> all = app().getStore().snapshot();
        if (requestCount != null) requestCount.setText(java.text.NumberFormat.getIntegerInstance().format(all.size()));
        if (trafficAdapter == null) return;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Exchange> filtered = new ArrayList<>();
        for (Exchange e : all) {
            if (trafficFilter.equals("HTTPS") && !e.tls) continue;
            if (trafficFilter.equals("Errors") && !hasError(e)) continue;
            String search = safe(e.method) + " " + safe(e.url) + " " + e.status + " " + safe(e.error);
            if (needle.isEmpty() || search.toLowerCase(Locale.ROOT).contains(needle)) filtered.add(e);
        }
        trafficAdapter.submit(filtered);
        trafficCount.setText(java.text.NumberFormat.getIntegerInstance().format(filtered.size())); trafficEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshCaptureState() {
        if (isDestroyed()) return;
        boolean running = CaptureVpnService.isRunning();
        topStatus.setText(running ? getString(R.string.capture_live) : getString(R.string.capture_offline)); topStatus.setTextColor(running ? PRIMARY : MUTED);
        if (captureTitle == null) return;
        captureTitle.setText(running ? getString(R.string.capture_running) : getString(R.string.capture_ready));
        String error = CaptureVpnService.lastError();
        captureSubtitle.setText(running ? getString(R.string.capture_local) : (error == null || error.isEmpty() ? getString(R.string.capture_no_root) : trim(error, 100)));
        captureButton.setText(running ? getString(R.string.capture_stop) : getString(R.string.capture_start)); captureButton.setIcon(icon(running ? GlyphDrawable.Kind.STOP : GlyphDrawable.Kind.PLAY, PRIMARY_DARK));
        if (transferCount != null) transferCount.setText(bytes(CaptureVpnService.bytesUp() + CaptureVpnService.bytesDown()));
    }

    private void toggleCapture() {
        if (CaptureVpnService.isRunning()) {
            startService(new Intent(this, CaptureVpnService.class).setAction(CaptureVpnService.ACTION_STOP));
            main.postDelayed(this::refreshCaptureState, 250); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        requestVpnPermission();
    }

    private void requestVpnPermission() {
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION); else startCapture();
    }

    @Override public void onRequestPermissionsResult(int request, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) requestVpnPermission();
    }

    private void startCapture() {
        app().applySettings();
        ContextCompat.startForegroundService(this, new Intent(this, CaptureVpnService.class).setAction(CaptureVpnService.ACTION_START));
        toast("Starting local VPN…"); main.postDelayed(this::refreshCaptureState, 700);
    }

    private View buildIntercept() {
        LinearLayout body = scrollColumn();
        addPageTitle(body, getString(R.string.nav_intercept), "Pause. Inspect. Decide.");
        MaterialCardView card = card(SURFACE, 22); LinearLayout inner = column(); inner.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout toggleRow = row(); toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column(); labels.addView(text("Request interception", 17, TEXT)); addMargin(labels, text("Review requests before they leave", 12, MUTED), 5);
        toggleRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialSwitch toggle = new MaterialSwitch(this); toggle.setContentDescription("Enable request interception"); toggle.setChecked(app().getInterceptor().isEnabled());
        toggle.setOnCheckedChangeListener((v, enabled) -> { app().getInterceptor().setEnabled(enabled); refreshIntercept(); }); toggleRow.addView(toggle); inner.addView(toggleRow);
        TextView note = text("Turn on while capturing to hold HTTP requests. Forward an edited request, or drop it. Turning interception off releases the queue.", 12, MUTED); note.setLineSpacing(dp(3), 1); addMargin(inner, note, 14);
        card.addView(inner); body.addView(card);
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL); TextView queue = text("Waiting for a decision", 18, TEXT); heading.addView(queue, new LinearLayout.LayoutParams(0, -2, 1)); interceptCount = label("0 PENDING", PRIMARY); heading.addView(interceptCount); addMargin(body, heading, 24);
        pendingContainer = column(); addMargin(body, pendingContainer, 12); return asScroll(body);
    }

    private void refreshIntercept() {
        if (navigation == null || isDestroyed()) return;
        List<InterceptController.Pending> pending = app().getInterceptor().pending();
        if (pending.isEmpty()) navigation.removeBadge(INTERCEPT);
        else { navigation.getOrCreateBadge(INTERCEPT).setNumber(pending.size()); navigation.getOrCreateBadge(INTERCEPT).setBackgroundColor(PRIMARY); navigation.getOrCreateBadge(INTERCEPT).setBadgeTextColor(PRIMARY_DARK); }
        if (pendingContainer == null) return;
        pendingContainer.removeAllViews(); interceptCount.setText(getResources().getQuantityString(R.plurals.pending_requests,pending.size(),pending.size()));
        if (pending.isEmpty()) {
            View empty = empty(GlyphDrawable.Kind.PAUSE, app().getInterceptor().isEnabled() ? "Listening for the next request" : "Let requests flow, or pause them", app().getInterceptor().isEnabled() ? "Keep your session running. New HTTP requests\nwill wait here for your review." : "Enable interception above when you want\nto inspect and edit a request in flight.");
            pendingContainer.addView(empty, new LinearLayout.LayoutParams(-1, dp(280))); return;
        }
        for (InterceptController.Pending p : pending) {
            MaterialCardView card = card(SURFACE, 18); LinearLayout line = column(); line.setPadding(dp(16), dp(15), dp(16), dp(15));
            line.addView(label(safe(p.exchange.method) + "   ·   REQUEST " + p.id, PRIMARY));
            TextView url = text(safe(p.exchange.url), 14, TEXT); url.setMaxLines(2); addMargin(line, url, 8);
            TextView action = text("Review request  →", 12, MUTED); addMargin(line, action, 12); card.addView(line);
            card.setOnClickListener(v -> reviewPending(p)); addMargin(pendingContainer, card, 9);
        }
    }

    private void reviewPending(InterceptController.Pending pending) {
        LinearLayout body = column(); body.setPadding(dp(20), dp(2), dp(20), 0);
        TextView target = text(safe(pending.exchange.url), 12, MUTED); target.setMaxLines(2); body.addView(target);
        if (pending.rawRequest.length > EDIT_LIMIT) {
            TextView note = text("This request is larger than the 1 MiB editor limit. Forward sends the complete original request without changes.", 12, AMBER); note.setLineSpacing(dp(3), 1); addMargin(body, note, 12);
            ScrollView preview = new ScrollView(this); preview.addView(code(new String(pending.rawRequest, 0, Math.min(32768, pending.rawRequest.length), StandardCharsets.ISO_8859_1) + "\n… read-only preview")); addMargin(body, preview, 12); preview.setLayoutParams(spaced(-1, dp(280), 12));
            new MaterialAlertDialogBuilder(this).setTitle("Review request #" + pending.id).setView(body).setPositiveButton("Forward original", (dialog, which) -> app().getInterceptor().forward(pending.id, pending.rawRequest)).setNegativeButton("Drop", (dialog, which) -> app().getInterceptor().drop(pending.id)).setNeutralButton("Keep waiting", null).show(); return;
        }
        EditText editor = rawEditor(new String(pending.rawRequest, StandardCharsets.ISO_8859_1), 15); addMargin(body, editor, 12);
        new MaterialAlertDialogBuilder(this).setTitle("Review request #" + pending.id).setView(body)
                .setPositiveButton("Forward", (dialog, which) -> { app().getInterceptor().forward(pending.id, canonicalRaw(editor.getText().toString())); refreshIntercept(); })
                .setNegativeButton("Drop", (dialog, which) -> { app().getInterceptor().drop(pending.id); refreshIntercept(); })
                .setNeutralButton("Keep waiting", null).show();
    }

    private View buildRepeater() {
        LinearLayout body = scrollColumn(); addPageTitle(body, getString(R.string.nav_repeater), "Make one change. See what happens.");
        TextInputLayout url = field("Target URL", false, 1); urlEditor = (TextInputEditText)url.getEditText(); urlEditor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); urlEditor.setText(repeaterUrl); body.addView(url);
        LinearLayout protocolRow=row();protocolRow.setGravity(Gravity.CENTER_VERTICAL);protocolRow.addView(label("PROTOCOL",MUTED),new LinearLayout.LayoutParams(0,-2,1));
        protocolEditor=new Spinner(this);String[] protocols={"AUTO","HTTP/1.1","HTTP/2","HTTP/3"};protocolEditor.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,protocols));
        for(int i=0;i<protocols.length;i++)if(protocols[i].equals(repeaterProtocol))protocolEditor.setSelection(i);protocolRow.addView(protocolEditor);addMargin(body,protocolRow,12);
        LinearLayout requestLabel = row(); requestLabel.setGravity(Gravity.CENTER_VERTICAL); requestLabel.addView(label("RAW REQUEST", MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton template = textButton("From URL"); template.setOnClickListener(v -> templateFromUrl()); requestLabel.addView(template); addMargin(body, requestLabel, 10);
        TextInputLayout request = field("HTTP/1.1 request", true, 12); request.setHintEnabled(false); requestEditor = (TextInputEditText)request.getEditText(); requestEditor.setText(repeaterRaw); requestEditor.setHorizontallyScrolling(true); body.addView(request);
        TextView note = text("The target URL selects the server and TLS host. Edit messages as HTTP/1 text; Sprig encodes them using the selected wire protocol. AUTO negotiates HTTP/2 or HTTP/1.1.", 11, MUTED); note.setLineSpacing(dp(2), 1); addMargin(body, note, 9);
        replayButton = button(replayBusy ? getString(R.string.sending) : getString(R.string.send_request), GlyphDrawable.Kind.SEND, true); replayButton.setEnabled(!replayBusy); replayButton.setOnClickListener(v -> sendReplay()); addMargin(body, replayButton, 16);
        MaterialButton intruder=button("Send to Intruder",GlyphDrawable.Kind.TOOLS,false);
        intruder.setOnClickListener(v->{try{captureRepeater();IntruderActivity.prepareDraft(repeaterUrl,canonicalRaw(repeaterRaw),repeaterProtocol);startActivity(new Intent(this,IntruderActivity.class));}catch(IllegalArgumentException error){showError("Cannot open Intruder draft",error);}});addMargin(body,intruder,6);
        LinearLayout responseLabel = row(); responseLabel.setGravity(Gravity.CENTER_VERTICAL); replayMeta = label(lastReplayMeta, MUTED); replayMeta.setMaxLines(2); responseLabel.addView(replayMeta, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton copy = iconButton(GlyphDrawable.Kind.COPY, "Copy response"); copy.setOnClickListener(v -> copy("HTTP response", lastReplayResponse)); responseLabel.addView(copy, new LinearLayout.LayoutParams(dp(44), dp(44))); addMargin(body, responseLabel, 18);
        replayResponse = code(lastReplayResponse); MaterialCardView response = card(SURFACE, 18); response.addView(replayResponse); body.addView(response);
        return asScroll(body);
    }

    private void captureRepeater() {
        if (protocolEditor != null) repeaterProtocol=protocolEditor.getSelectedItem().toString();
        if (urlEditor != null) repeaterUrl = urlEditor.getText().toString();
        if (requestEditor != null) repeaterRaw = requestEditor.getText().toString();
    }

    private void retainDraft() {
        captureRepeater(); DRAFT.initialized = true; DRAFT.url = repeaterUrl; DRAFT.request = repeaterRaw;
        DRAFT.decoder = decoderInput; DRAFT.left = comparerLeft; DRAFT.right = comparerRight; DRAFT.decoderMode = decoderMode;
        DRAFT.protocol = repeaterProtocol;
    }

    private void templateFromUrl() {
        try {
            Uri uri = Uri.parse(urlEditor.getText().toString().trim());
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException("Enter an http:// or https:// URL");
            String path = uri.getEncodedPath(); if (path == null || path.isEmpty()) path = "/";
            if (uri.getEncodedQuery() != null) path += "?" + uri.getEncodedQuery();
            String host = uri.getHost(); if (uri.getPort() != -1) host += ":" + uri.getPort();
            String wireTemplate="GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAccept: */*\r\nConnection: close\r\n\r\n";
            requestEditor.setText(wireTemplate);
        } catch (Exception e) { showError("Cannot create request", e); }
    }

    private void sendReplay() {
        captureRepeater(); if (replayBusy) return;
        String url = repeaterUrl.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) { toast("Use an http:// or https:// target URL"); return; }
        if (repeaterRaw.trim().isEmpty()) { toast("Enter a raw HTTP request"); return; }
        replayBusy = true; replayButton.setEnabled(false); replayButton.setText(getString(R.string.sending));
        final byte[] raw = canonicalRaw(repeaterRaw);
        final String selectedProtocol = repeaterProtocol;
        if(!submitWork(() -> {
            try {
                Exchange exchange = app().getProxy().replay(url, raw, selectedProtocol);
                String response = rawResponse(exchange); String metadata = exchange.protocol + "  ·  " + exchange.status + "  ·  " + exchange.durationMillis + " ms  ·  " + bytes(exchange.responseBody == null ? 0 : exchange.responseBody.length);
                if (exchange.error != null && !exchange.error.isEmpty()) response += "\n\n" + exchange.error;
                final String result = response;
                onMain(() -> { lastReplayResponse = result; lastReplayMeta = metadata; if (replayResponse != null) replayResponse.setText(result); if (replayMeta != null) replayMeta.setText(metadata); finishReplay(); });
            } catch (Exception e) { onMain(() -> { lastReplayResponse = message(e); lastReplayMeta = "REQUEST FAILED"; if (replayResponse != null) replayResponse.setText(lastReplayResponse); if (replayMeta != null) replayMeta.setText(lastReplayMeta); finishReplay(); }); }
        })) finishReplay();
    }

    private boolean submitWork(Runnable task){return UiWorkQueue.submit(work,task,()->toast(getString(R.string.workspace_busy)));}

    private void finishReplay() { replayBusy = false; if (replayButton != null) { replayButton.setEnabled(true); replayButton.setText(getString(R.string.send_request)); } }

    private View buildTools() {
        LinearLayout body = scrollColumn(); addPageTitle(body, getString(R.string.nav_tools), "Small tools. Precise answers.");
        addWorkspaceCard(body,"Intruder","Run position-based payload attacks with controlled concurrency, matching and evidence.","Open Intruder",IntruderActivity.class);
        addWorkspaceCard(body,"WebSocket","Inspect live ws/wss messages, edit held traffic and replay text or binary payloads.","Open WebSocket",WebSocketActivity.class);
        addWorkspaceCard(body,"Scanner","Crawl a scoped target, run bounded active checks and review reproducible evidence.","Open Scanner",ScannerActivity.class);
        addWorkspaceCard(body,"Collaborator","Generate private callback addresses and collect DNS, HTTP and SMTP interactions.","Open Collaborator",CollaboratorActivity.class);
        MaterialCardView decoder = card(SURFACE, 22); LinearLayout inner = column(); inner.setPadding(dp(18), dp(18), dp(18), dp(18));
        inner.addView(text("Decoder", 19, TEXT)); addMargin(inner, text("Transform text without leaving your workspace.", 12, MUTED), 6);
        Spinner mode = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Base64", "URL encoding", "Hexadecimal", "SHA-256"}); mode.setAdapter(adapter); mode.setSelection(decoderMode); addMargin(inner, mode, 12);
        TextInputLayout input = field("Input", true, 4); input.getEditText().setText(decoderInput); watch(input.getEditText(), value -> decoderInput = value); addMargin(inner, input, 10);
        TextView result = code("Output appears here"); result.setTextColor(MUTED); result.setMinHeight(dp(80));
        LinearLayout actions = row(); MaterialButton encode = button("Encode", null, false), decode = button("Decode", null, false);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1); actions.addView(encode, half); LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1); half2.leftMargin = dp(10); actions.addView(decode, half2); addMargin(inner, actions, 12);
        encode.setOnClickListener(v -> decodeText(mode.getSelectedItemPosition(), input.getEditText().getText().toString(), false, result));
        decode.setOnClickListener(v -> decodeText(mode.getSelectedItemPosition(), input.getEditText().getText().toString(), true, result));
        MaterialCardView output = card(BACKGROUND, 14); output.addView(result); addMargin(inner, output, 12);
        MaterialButton copyResult = textButton("Copy output"); copyResult.setOnClickListener(v -> copy("Decoder output", result.getText().toString())); inner.addView(copyResult); decoder.addView(inner); body.addView(decoder);

        MaterialCardView comparer = card(SURFACE, 22); LinearLayout compare = column(); compare.setPadding(dp(18), dp(18), dp(18), dp(18)); compare.addView(text("Comparer", 19, TEXT));
        addMargin(compare, text("Compare two responses, tokens or text samples.", 12, MUTED), 6);
        TextInputLayout left = field("Original", true, 4); left.getEditText().setText(comparerLeft); watch(left.getEditText(), value -> comparerLeft = value); addMargin(compare, left, 16);
        TextInputLayout right = field("Modified", true, 4); right.getEditText().setText(comparerRight); watch(right.getEditText(), value -> comparerRight = value); addMargin(compare, right, 12);
        MaterialButton compareButton = button("Compare text", GlyphDrawable.Kind.FILTER, false); addMargin(compare, compareButton, 12);
        TextView difference = code("Removed lines appear in red. Added lines appear in green."); difference.setTextColor(MUTED); addMargin(compare, difference, 10);
        compareButton.setOnClickListener(v -> {
            String a = left.getEditText().getText().toString(), b = right.getEditText().getText().toString();
            if (a.equals(b)) { difference.setTextColor(PRIMARY); difference.setText(getString(R.string.compare_identical)); return; }
            String[] aa = a.split("\\n", -1), bb = b.split("\\n", -1);
            if (aa.length > 500 || bb.length > 500) { toast("Compare up to 500 lines per input"); return; }
            difference.setText(diff(aa, bb));
        });
        comparer.addView(compare); addMargin(body, comparer, 18);
        MaterialCardView payloads = card(SURFACE, 22); LinearLayout batch = column(); batch.setPadding(dp(18), dp(18), dp(18), dp(18));
        batch.addView(text("Payload runner", 19, TEXT)); TextView intro = text("Replay a request with your own payload list. One marker, one request at a time, with a pause between requests.", 12, MUTED); intro.setLineSpacing(dp(3), 1); addMargin(batch, intro, 7);
        MaterialButton openBatch = button("Configure payload replay", GlyphDrawable.Kind.SEND, false); openBatch.setOnClickListener(v -> showPayloadRunner()); addMargin(batch, openBatch, 16);
        payloads.addView(batch); addMargin(body, payloads, 18); return asScroll(body);
    }

    private void addWorkspaceCard(LinearLayout body,String title,String detail,String action,Class<?> activity) {
        MaterialCardView panel=card(SURFACE,22);LinearLayout box=column();box.setPadding(dp(18),dp(18),dp(18),dp(18));
        box.addView(text(title,21,PRIMARY));addMargin(box,text(detail,12,MUTED),8);MaterialButton open=button(action,GlyphDrawable.Kind.TOOLS,true);
        open.setOnClickListener(v->{captureRepeater();startActivity(new Intent(this,activity).putExtra("seed_url",repeaterUrl));});addMargin(box,open,14);panel.addView(box);addMargin(body,panel,14);
    }

    private void decodeText(int mode, String input, boolean decode, TextView output) {
        decoderMode = mode;
        try {
            String result;
            switch (mode) {
                case 1: result = decode ? URLDecoder.decode(input, "UTF-8") : URLEncoder.encode(input, "UTF-8"); break;
                case 2: result = decode ? new String(fromHex(input), StandardCharsets.UTF_8) : toHex(input.getBytes(StandardCharsets.UTF_8)); break;
                case 3: if (decode) throw new IllegalArgumentException("SHA-256 is a digest and cannot be decoded"); result = toHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); break;
                default: result = decode ? new String(Base64.getMimeDecoder().decode(input), StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
            }
            output.setTextColor(TEXT); output.setText(result);
        } catch (Exception e) { output.setTextColor(ERROR); output.setText(message(e)); }
    }

    private View buildSettings() {
        LinearLayout body = scrollColumn(); addPageTitle(body, getString(R.string.nav_settings), "Your session, your rules.");
        MaterialCardView ca = card(PRIMARY_DARK, 22); LinearLayout certificate = column(); certificate.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView caTitle = text("HTTPS inspection", 20, PRIMARY); caTitle.setCompoundDrawablesWithIntrinsicBounds(icon(GlyphDrawable.Kind.CERTIFICATE, PRIMARY), null, null, null); caTitle.setCompoundDrawablePadding(dp(10)); certificate.addView(caTitle);
        TextView caText = text("Install this device’s Sprig CA certificate in your test environment to inspect TLS traffic. Apps must trust the certificate; certificate pinning remains effective.", 12, Color.rgb(176, 207, 184)); caText.setLineSpacing(dp(3), 1); addMargin(certificate, caText, 12);
        MaterialButton export = button("Export CA certificate", GlyphDrawable.Kind.EXPORT, true); export.setOnClickListener(v -> exportCa()); addMargin(certificate, export, 15);
        MaterialButton instructions = textButton("Certificate setup"); instructions.setTextColor(PRIMARY); instructions.setOnClickListener(v -> showCertificateSetup()); certificate.addView(instructions); ca.addView(certificate); body.addView(ca);

        addMargin(body, label("CAPTURE", MUTED), 24);
        body.addView(settingToggle("Inspect HTTPS", "Issue local certificates for hosts in scope", "mitm", true), spaced(-1, -2, 10));
        body.addView(settingToggle("Inspect HTTP/3", "Terminate in-scope QUIC connections with the local CA", "http3", true), spaced(-1, -2, 10));
        body.addView(settingToggle("Prefer inspectable HTTPS", "Block QUIC when HTTP/3 inspection cannot handle the flow", "blocked_quic", true), spaced(-1, -2, 10));

        MaterialCardView apps = card(SURFACE, 20); LinearLayout appsBody = column(); appsBody.setPadding(dp(18), dp(17), dp(18), dp(15)); appsBody.addView(text("Apps to capture", 16, TEXT));
        Set<String> selected = app().getPreferences().getStringSet("selected_apps", Collections.emptySet());
        addMargin(appsBody, text(selected.isEmpty() ? "All apps, except Sprig itself" : selected.size() + " selected apps", 12, MUTED), 6);
        MaterialButton choose = textButton("Choose apps"); choose.setOnClickListener(v -> chooseApps()); appsBody.addView(choose); apps.addView(appsBody); addMargin(body, apps, 10);

        MaterialCardView scope = card(SURFACE, 20); LinearLayout scopeBody = column(); scopeBody.setPadding(dp(18), dp(17), dp(18), dp(17)); scopeBody.addView(text("HTTPS host scope", 16, TEXT));
        TextView explanation = text("One host or domain per line. Leave empty to inspect every host. Other hosts pass through without TLS inspection.", 12, MUTED); explanation.setLineSpacing(dp(2), 1); addMargin(scopeBody, explanation, 7);
        TextInputLayout hosts = field("example.com\napi.example.org", false, 4); hosts.getEditText().setText(app().getPreferences().getString("scope", "")); addMargin(scopeBody, hosts, 12);
        MaterialButton save = textButton("Save scope"); save.setOnClickListener(v -> {
            String value = hosts.getEditText().getText().toString().trim();
            try {
                for (String host : value.split("[\\s,]+")) { if (host.isEmpty()) continue; CertificateAuthority.normalizeHost(host.startsWith("*.") ? host.substring(2) : host); }
                app().getPreferences().edit().putString("scope", value).apply(); app().applySettings(); hosts.setError(null); toast("Host scope saved");
            } catch (Exception error) { hosts.setError(message(error)); }
        }); scopeBody.addView(save); scope.addView(scopeBody); addMargin(body, scope, 10);
        TextView restart = text("App selection and QUIC changes apply to the next capture. Stop and restart an active session after changing them.", 11, MUTED); restart.setLineSpacing(dp(2), 1); addMargin(body, restart, 12);

        addMargin(body, label("SESSION", MUTED), 24);
        MaterialButton har = button("Export traffic as HAR", GlyphDrawable.Kind.EXPORT, false); har.setOnClickListener(v -> exportHar()); addMargin(body, har, 12);
        MaterialButton clear = button("Clear HTTP history", GlyphDrawable.Kind.TRASH, false); clear.setOnClickListener(v -> confirmClear()); addMargin(body, clear, 8);
        TextView foot = text("SPRIG  /  JAVA EDITION\nLocal inspection workspace · No cloud connection required", 10, MUTED); foot.setLineSpacing(dp(5), 1); foot.setGravity(Gravity.CENTER); addMargin(body, foot, 30);
        return asScroll(body);
    }

    private View settingToggle(String title, String subtitle, String key, boolean fallback) {
        MaterialCardView card = card(SURFACE, 20); LinearLayout row = row(); row.setPadding(dp(18), dp(14), dp(12), dp(14)); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = column(); words.addView(text(title, 16, TEXT)); TextView description = text(subtitle, 11, MUTED); description.setLineSpacing(dp(2), 1); addMargin(words, description, 6); row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialSwitch toggle = new MaterialSwitch(this); toggle.setContentDescription(title); toggle.setChecked(app().getPreferences().getBoolean(key, fallback)); toggle.setOnCheckedChangeListener((b, value) -> { app().getPreferences().edit().putBoolean(key, value).apply(); app().applySettings(); }); row.addView(toggle); card.addView(row); return card;
    }

    private void chooseApps() {
        toast("Loading available apps…");
        submitWork(() -> {
            List<AppChoice> choices = new ArrayList<>(); PackageManager pm = getPackageManager();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (info.packageName.equals(getPackageName())) continue;
                if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                choices.add(new AppChoice(info.loadLabel(pm).toString(), info.packageName));
            }
            choices.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
            onMain(() -> {
                Set<String> selected = new HashSet<>(app().getPreferences().getStringSet("selected_apps", Collections.emptySet()));
                String[] names = new String[choices.size()]; boolean[] checked = new boolean[choices.size()];
                for (int i = 0; i < choices.size(); i++) { names[i] = choices.get(i).name + "\n" + choices.get(i).packageName; checked[i] = selected.contains(choices.get(i).packageName); }
                new MaterialAlertDialogBuilder(this).setTitle("Apps to capture").setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> { String packageName = choices.get(which).packageName; if (isChecked) selected.add(packageName); else selected.remove(packageName); })
                        .setPositiveButton(getString(R.string.common_save), (dialog, which) -> { app().getPreferences().edit().putStringSet("selected_apps", new HashSet<>(selected)).apply(); showPage(SETTINGS_TAB); toast("App selection saved for the next capture"); })
                        .setNeutralButton("All apps", (dialog, which) -> { app().getPreferences().edit().putStringSet("selected_apps", Collections.emptySet()).apply(); showPage(SETTINGS_TAB); })
                        .setNegativeButton(getString(R.string.common_cancel), null).show();
            });
        });
    }

    private void showCertificateSetup() {
        LinearLayout body = column(); body.setPadding(dp(24), dp(4), dp(24), dp(4));
        TextView instructions = text("1. Export the CA certificate to a location on this device.\n\n2. Open Android Settings → Security & privacy → More security settings → Install a certificate → CA certificate. Menu names vary by device.\n\n3. Select the exported sprig-ca.cer.\n\n4. Configure your own test app to trust user certificates, then enable HTTPS inspection and start capture.\n\nSprig does not bypass certificate pinning. Remove the CA from Android’s trusted credentials when you no longer need it.", 13, TEXT); instructions.setLineSpacing(dp(3), 1); body.addView(instructions);
        TextView fingerprint = text("Loading CA fingerprint…", 10, MUTED); fingerprint.setTypeface(Typeface.MONOSPACE); fingerprint.setTextIsSelectable(true); addMargin(body, fingerprint, 18);
        new MaterialAlertDialogBuilder(this).setTitle("Trust the Sprig CA").setView(body).setPositiveButton("Open security settings", (dialog, which) -> {
            try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception e) { showError("Cannot open settings", e); }
        }).setNegativeButton(getString(R.string.common_close), null).show();
        if(!submitWork(() -> { try { String value = app().getProxy().getAuthority().fingerprint(); onMain(() -> fingerprint.setText(getString(R.string.fingerprint,value))); } catch (Exception e) { onMain(() -> fingerprint.setText(message(e))); } })) fingerprint.setText(R.string.workspace_busy);
    }

    private void exportHar() { createDocument(EXPORT_HAR, "application/json", "sprig-session-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".har"); }
    private void exportCa() { createDocument(EXPORT_CA, "application/x-x509-ca-cert", "sprig-ca.cer"); }
    private void createDocument(int code, String type, String name) {
        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, name), code); }
        catch (Exception e) { showError("Cannot open document picker", e); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VPN_PERMISSION) { if (result == Activity.RESULT_OK) startCapture(); else toast("VPN permission was not granted"); return; }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || (request != EXPORT_HAR && request != EXPORT_CA)) return;
        Uri destination = data.getData();
        submitWork(() -> {
            try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                if (stream == null) throw new IllegalStateException("The selected document could not be opened");
                if (request == EXPORT_HAR) app().exportHar(stream); else stream.write(app().getProxy().getAuthority().certificateDer());
                onMain(() -> toast(request == EXPORT_HAR ? "HAR file exported" : "CA certificate exported"));
            } catch (Exception e) { onMain(() -> showError("Export failed", e)); }
        });
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this).setTitle("Clear HTTP history?").setMessage("This removes the current session’s captured requests and responses from this device.").setPositiveButton("Clear history", (dialog, which) -> { app().getStore().clear(); refreshTraffic(); toast("HTTP history cleared"); }).setNegativeButton(getString(R.string.common_cancel), null).show();
    }

    private void showPayloadRunner() {
        if (payloadRunner != null && !payloadRunner.isCancelled()) { toast("A payload replay is already running"); return; }
        captureRepeater();
        LinearLayout body = column(); body.setPadding(dp(20), 0, dp(20), 0);
        TextInputLayout target = field("Target URL", false, 1); target.getEditText().setText(repeaterUrl); body.addView(target);
        TextView guide = text("Place {{payload}} in the request where each value should go. Add one payload per line. Results also appear in HTTP history.", 12, MUTED); guide.setLineSpacing(dp(2), 1); addMargin(body, guide, 12);
        TextInputLayout template = field("Raw request template", true, 7); template.getEditText().setText(repeaterRaw); addMargin(body, template, 12);
        TextInputLayout values = field("Payloads · one per line", true, 4); addMargin(body, values, 12);
        TextInputLayout delay = field("Delay between requests (ms)", false, 1); delay.getEditText().setInputType(android.text.InputType.TYPE_CLASS_NUMBER); delay.getEditText().setText(R.string.payload_default_delay); addMargin(body, delay, 12);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Payload replay").setView(scroll).setPositiveButton("Run payloads", null).setNegativeButton(getString(R.string.common_cancel), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = target.getEditText().getText().toString().trim(), raw = template.getEditText().getText().toString();
            String list = values.getEditText().getText().toString(); long pause;
            try { pause = Long.parseLong(delay.getEditText().getText().toString()); } catch (NumberFormatException e) { delay.setError("Enter a delay in milliseconds"); return; }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) { target.setError("Use an http:// or https:// target URL"); return; }
            if (!raw.contains("{{payload}}")) { template.setError("Add a {{payload}} replacement marker"); return; }
            if (pause < 100 || pause > 60000) { delay.setError("Use a delay from 100 to 60000 ms"); return; }
            if (list.isEmpty()) { values.setError("Enter at least one payload"); return; }
            List<String> payloads = java.util.Arrays.asList(list.split("\\r?\\n", -1));
            if (payloads.size() > 500) { values.setError("Use no more than 500 payloads"); return; }
            dialog.dismiss(); runPayloads(url, new String(canonicalRaw(raw), StandardCharsets.ISO_8859_1), payloads, pause);
        })); dialog.show();
    }

    private void runPayloads(String url, String template, List<String> payloads, long pause) {
        PayloadRunner runner = new PayloadRunner(); payloadRunner = runner;
        LinearLayout body = column(); body.setPadding(dp(20), 0, dp(20), 0); TextView progress = text("Starting 0 / " + payloads.size(), 13, MUTED); body.addView(progress);
        ScrollView scroll = new ScrollView(this); TextView results = code(""); results.setTextSize(10); scroll.addView(results); body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(320)));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Payload replay").setView(body).setNegativeButton("Stop", (d, which) -> runner.cancel()).setPositiveButton("Keep in background", null).create(); dialog.setCanceledOnTouchOutside(false); dialog.setOnCancelListener(d -> runner.cancel()); dialog.show();
        StringBuilder log = new StringBuilder();
        if(!UiWorkQueue.submit(payloadWork,() -> {
            try {
                runner.run(app().getProxy(), url, template, payloads, pause, (index, payload, exchange, error) -> {
                    String line = (index + 1) + "  " + (exchange == null ? "ERR" : exchange.status) + "  " + (exchange == null ? safe(error) : exchange.durationMillis + " ms · " + bytes(exchange.responseBody == null ? 0 : exchange.responseBody.length)) + "  " + trim(payload, 70) + "\n";
                    onMain(() -> { log.append(line); results.setText(log.toString()); progress.setText(getString(R.string.completed_progress,index+1,payloads.size())); });
                });
                onMain(() -> { progress.setText(runner.isCancelled() ? getString(R.string.replay_stopped) : getResources().getQuantityString(R.plurals.completed_requests,payloads.size(),payloads.size())); dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setText(getString(R.string.common_close)); toast(runner.isCancelled() ? "Payload replay stopped" : "Payload replay complete"); });
            } catch (Exception e) { onMain(() -> { progress.setText(getString(R.string.replay_failed)); results.setText(message(e)); dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setText(getString(R.string.common_close)); }); }
            finally { runner.cancel(); }
        },()->{runner.cancel();progress.setText(R.string.workspace_busy);dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setText(R.string.common_close);})) runner.cancel();
    }

    private void showExchange(Exchange exchange) {
        LinearLayout body = column(); body.setPadding(dp(20), 0, dp(20), 0);
        TextView target = text(safe(exchange.url), 12, MUTED); target.setMaxLines(3); target.setTextIsSelectable(true); body.addView(target);
        boolean tunnel = isTunnel(exchange), httpRequest = hasHttpRequest(exchange);
        String status = tunnel && !hasError(exchange) ? "TCP pass-through" : exchange.status == 0 ? "No response" : exchange.status + "";
        String protocolPath=connectionLabel(exchange);if(!safe(exchange.upstreamProtocol).isEmpty()&&!safe(exchange.protocol).equals(exchange.upstreamProtocol))protocolPath+=" → "+exchange.upstreamProtocol;
        TextView meta = label(status + "  ·  " + exchange.durationMillis + " ms  ·  " + protocolPath, hasError(exchange) ? ERROR : tunnel ? MUTED : PRIMARY); addMargin(body, meta, 10);
        ChipGroup tabs = new ChipGroup(this); tabs.setSingleSelection(true); tabs.setSelectionRequired(true); Chip request = chip("Request"), response = chip("Response"); tabs.addView(request); tabs.addView(response); request.setChecked(true); addMargin(body, tabs, 10);
        String fullRequest = httpRequest ? rawRequest(exchange) : tunnel ? "TCP pass-through\n\nThis connection was forwarded without inspecting HTTP message content." : "No complete HTTP request was captured.";
        String fullResponse = httpRequest ? rawResponse(exchange) : "No HTTP response content was inspected.";
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); TextView raw = code(fullRequest); raw.setTextSize(11); raw.setHorizontallyScrolling(true); scroll.addView(raw); body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(330)));
        TextView previewNote = text("The display shows a shortened preview. Copy includes the complete captured message.", 11, MUTED); previewNote.setVisibility(fullRequest.length() > 256 * 1024 ? View.VISIBLE : View.GONE); addMargin(body, previewNote, 6);
        request.setOnCheckedChangeListener((v, checked) -> { if (checked) { raw.setText(trim(fullRequest, 256 * 1024)); previewNote.setVisibility(fullRequest.length() > 256 * 1024 ? View.VISIBLE : View.GONE); } });
        response.setOnCheckedChangeListener((v, checked) -> { if (checked) { raw.setText(trim(fullResponse, 256 * 1024)); previewNote.setVisibility(fullResponse.length() > 256 * 1024 ? View.VISIBLE : View.GONE); } });
        if (exchange.error != null && !exchange.error.isEmpty()) { TextView error = text(exchange.error, 11, ERROR); addMargin(body, error, 10); }
        if (exchange.truncated) addMargin(body, text("The capture limit omitted part of this exchange. Copy includes all captured bytes; omitted content is unavailable.", 11, AMBER), 8);
        List<PassiveAnalyzer.Finding> findings = PassiveAnalyzer.analyze(exchange);
        if (!findings.isEmpty()) {
            MaterialButton observations = textButton(findings.size() + " passive observations");
            observations.setOnClickListener(v -> showFindings(findings)); addMargin(body, observations, 6);
        }
        boolean requestMayBeIncomplete = exchange.truncated && exchange.requestBody != null && exchange.requestBody.length >= 512 * 1024;
        if (requestMayBeIncomplete) addMargin(body, text("This captured request body may be incomplete. Replay from the original request instead.", 11, AMBER), 6);
        androidx.appcompat.app.AlertDialog details = new MaterialAlertDialogBuilder(this).setTitle(safe(exchange.method) + "  ·  Request #" + exchange.id).setView(body)
                .setPositiveButton("Send to…", (dialog, which) -> {
                    String[] actions={getString(R.string.nav_repeater),"Intruder","Scanner · captured request"};
                    new MaterialAlertDialogBuilder(this).setTitle("Send captured request").setItems(actions,(choice,index)->{
                        if(index==0){repeaterUrl=safe(exchange.url);repeaterRaw=rawRequest(exchange);repeaterProtocol=exchange.protocol;urlEditor=requestEditor=null;protocolEditor=null;navigation.setSelectedItemId(REPEATER);}
                        else if(index==1)startActivity(new Intent(this,IntruderActivity.class).putExtra("seed_url",exchange.url).putExtra("exchange_id",exchange.id));
                        else startActivity(new Intent(this,ScannerActivity.class).putExtra("seed_url",exchange.url).putExtra("exchange_id",exchange.id));
                    }).show();
                })
                .setNeutralButton("Copy", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Captured HTTP message", request.isChecked() ? fullRequest : fullResponse));
                    toast(exchange.truncated ? "Copied captured bytes; omitted content is unavailable" : "Complete captured message copied");
                }).setNegativeButton(getString(R.string.common_close), null).show();
        details.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(httpRequest && !requestMayBeIncomplete);
        details.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setEnabled(httpRequest);
    }

    private void showFindings(List<PassiveAnalyzer.Finding> findings) {
        LinearLayout body = column(); body.setPadding(dp(22), 0, dp(22), dp(10));
        for (PassiveAnalyzer.Finding finding : findings) { TextView severity = label(finding.severity.toUpperCase(Locale.ROOT), finding.severity.equalsIgnoreCase("Medium") ? AMBER : MUTED); addMargin(body, severity, 16); addMargin(body, text(finding.title, 15, TEXT), 7); TextView detail = text(finding.detail, 12, MUTED); detail.setLineSpacing(dp(3), 1); addMargin(body, detail, 7); }
        new MaterialAlertDialogBuilder(this).setTitle("Passive observations").setView(asScroll(body)).setPositiveButton(getString(R.string.common_close), null).show();
    }

    private final class TrafficAdapter extends RecyclerView.Adapter<TrafficHolder> {
        private List<Exchange> items = Collections.emptyList();
        TrafficAdapter() { setHasStableIds(true); }
        void submit(List<Exchange> next) { items = next; notifyDataSetChanged(); }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @NonNull @Override public TrafficHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            MaterialCardView card = card(SURFACE, 17); RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2); params.bottomMargin = dp(8); card.setLayoutParams(params);
            LinearLayout body = column(); body.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout first = row(); first.setGravity(Gravity.CENTER_VERTICAL);
            TextView method = label("GET", PRIMARY); method.setTextSize(10); method.setMinWidth(dp(42)); first.addView(method);
            TextView path = text("/", 14, TEXT); path.setSingleLine(true); path.setEllipsize(android.text.TextUtils.TruncateAt.END); first.addView(path, new LinearLayout.LayoutParams(0, -2, 1));
            TextView status = text("200", 13, PRIMARY); status.setTypeface(Typeface.MONOSPACE); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.leftMargin = dp(10); first.addView(status, sp); body.addView(first);
            LinearLayout second = row(); second.setGravity(Gravity.CENTER_VERTICAL); TextView host = text("", 11, MUTED); host.setSingleLine(true); host.setEllipsize(android.text.TextUtils.TruncateAt.END); second.addView(host, new LinearLayout.LayoutParams(0, -2, 1));
            TextView timing = text("", 10, MUTED); LinearLayout.LayoutParams timingParams = new LinearLayout.LayoutParams(-2, -2); timingParams.leftMargin = dp(10); second.addView(timing, timingParams); addMargin(body, second, 7); card.addView(body);
            return new TrafficHolder(card, method, path, status, host, timing);
        }
        @Override public void onBindViewHolder(@NonNull TrafficHolder h, int position) {
            Exchange e = items.get(position); boolean tunnel = isTunnel(e); Uri uri = Uri.parse(safe(e.url)); String path = uri.getEncodedPath(); if (path == null || path.isEmpty()) path = "/"; if (uri.getEncodedQuery() != null) path += "?" + uri.getEncodedQuery();
            h.method.setText(safe(e.method)); h.method.setTextColor(tunnel ? MUTED : PRIMARY); h.path.setText(tunnel ? "Pass-through connection" : path);
            h.status.setText(tunnel && !hasError(e) ? "TCP" : e.status == 0 ? "ERR" : e.status + ""); h.status.setTextColor(hasError(e) ? ERROR : tunnel ? MUTED : (e.status >= 300 ? AMBER : PRIMARY));
            String host = uri.getHost() == null ? safe(e.url) : uri.getHost(); if (tunnel && uri.getPort() != -1) host += ":" + uri.getPort();
            h.host.setText(getString(R.string.raw_pair,connectionLabel(e),host)); h.timing.setText(tunnel?getString(R.string.duration_ms,e.durationMillis):getString(R.string.duration_and_bytes,e.durationMillis,bytes(e.responseBody==null?0:e.responseBody.length)));
            h.itemView.setOnClickListener(v -> showExchange(e));
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private static final class TrafficHolder extends RecyclerView.ViewHolder {
        final TextView method, path, status, host, timing;
        TrafficHolder(View view, TextView method, TextView path, TextView status, TextView host, TextView timing) { super(view); this.method = method; this.path = path; this.status = status; this.host = host; this.timing = timing; }
    }
    private static final class AppChoice { final String name, packageName; AppChoice(String name, String packageName) { this.name = name; this.packageName = packageName; } }
    private static final class EditorDraft { boolean initialized; int decoderMode; String url, request, decoder, left, right, protocol; }

    private void addPageTitle(LinearLayout body, String title, String subtitle) { TextView heading = text(title, 29, TEXT); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); body.addView(heading); TextView sub = text(subtitle, 13, MUTED); addMargin(body, sub, 7); View gap = new View(this); body.addView(gap, new LinearLayout.LayoutParams(1, dp(24))); }
    private LinearLayout scrollColumn() { LinearLayout body = column(); body.setPadding(dp(20), dp(6), dp(20), dp(30)); return body; }
    private View asScroll(LinearLayout body) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); return scroll; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private TextView text(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); view.setIncludeFontPadding(false); view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); return view; }
    private TextView label(String value, int color) { TextView view = text(value, 10, color); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); view.setLetterSpacing(0.08f); return view; }
    private TextView code(String value) { TextView view = text(trim(value, 256 * 1024), 12, TEXT); view.setTypeface(Typeface.MONOSPACE); view.setTextIsSelectable(true); view.setSaveEnabled(false); view.setPadding(dp(14), dp(14), dp(14), dp(14)); view.setLineSpacing(dp(3), 1); return view; }
    private MaterialCardView card(int color, int radius) { MaterialCardView card = new MaterialCardView(this); card.setCardBackgroundColor(color); card.setRadius(dp(radius)); card.setCardElevation(0); card.setStrokeWidth(0); card.setUseCompatPadding(false); return card; }
    private GradientDrawable round(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private GlyphDrawable icon(GlyphDrawable.Kind kind, int color) { return new GlyphDrawable(kind, color, dp(23)); }
    private MaterialButton button(String title, GlyphDrawable.Kind kind, boolean primary) {
        MaterialButton button = new MaterialButton(this); button.setText(title); button.setTextSize(13); button.setAllCaps(false); button.setCornerRadius(dp(15)); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setInsetTop(0); button.setInsetBottom(0); button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? PRIMARY : SURFACE_HIGH)); button.setTextColor(primary ? PRIMARY_DARK : PRIMARY); button.setIconTint(ColorStateList.valueOf(primary ? PRIMARY_DARK : PRIMARY)); button.setElevation(0);
        if (kind != null) { button.setIcon(icon(kind, primary ? PRIMARY_DARK : PRIMARY)); button.setIconSize(dp(18)); button.setIconPadding(dp(9)); button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START); }
        return button;
    }
    private MaterialButton textButton(String title) { MaterialButton button = button(title, null, false); button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT)); button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44)); button.setPadding(dp(6), dp(5), dp(6), dp(5)); return button; }
    private MaterialButton iconButton(GlyphDrawable.Kind kind, String description) { MaterialButton button = textButton(""); button.setContentDescription(description); button.setIcon(icon(kind, MUTED)); button.setIconTint(ColorStateList.valueOf(MUTED)); button.setIconSize(dp(20)); button.setIconPadding(0); button.setPadding(dp(10), dp(10), dp(10), dp(10)); button.setMinWidth(0); button.setMinimumWidth(0); button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START); return button; }
    private Chip chip(String title) { Chip chip = new Chip(this); chip.setText(title); chip.setTextSize(11); chip.setCheckable(true); chip.setCheckedIconVisible(false); chip.setChipBackgroundColor(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PRIMARY_DARK, SURFACE})); chip.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PRIMARY, MUTED})); chip.setChipStrokeWidth(0); chip.setEnsureMinTouchTargetSize(false); chip.setChipMinHeight(dp(30)); chip.setChipCornerRadius(dp(10)); chip.setId(View.generateViewId()); return chip; }
    private TextInputLayout field(String hint, boolean mono, int lines) {
        TextInputLayout wrap = new TextInputLayout(this); wrap.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE); wrap.setBoxCornerRadii(dp(13), dp(13), dp(13), dp(13)); wrap.setBoxStrokeColor(OUTLINE); wrap.setBoxBackgroundColor(SURFACE); wrap.setDefaultHintTextColor(ColorStateList.valueOf(MUTED)); wrap.setHintTextColor(ColorStateList.valueOf(PRIMARY)); wrap.setHint(hint); wrap.setBoxStrokeWidth(dp(1)); wrap.setBoxStrokeWidthFocused(dp(1));
        TextInputEditText edit = new TextInputEditText(wrap.getContext()); edit.setTextColor(TEXT); edit.setHintTextColor(MUTED); edit.setTextSize(mono ? 12 : 14); edit.setMinLines(lines); edit.setMaxLines(lines == 1 ? 1 : Math.max(lines, 20)); edit.setGravity(Gravity.TOP | Gravity.START); edit.setPadding(dp(14), dp(14), dp(14), dp(14)); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(EDIT_LIMIT)});
        edit.setSaveEnabled(false);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (lines == 1 ? 0 : android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (mono) edit.setTypeface(Typeface.MONOSPACE);
        wrap.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return wrap;
    }
    private EditText rawEditor(String value, int lines) { EditText edit = new EditText(this); edit.setSaveEnabled(false); edit.setTextColor(TEXT); edit.setBackground(round(SURFACE_HIGH, 14)); edit.setTextSize(11); edit.setTypeface(Typeface.MONOSPACE); edit.setMinLines(lines); edit.setMaxLines(lines); edit.setGravity(Gravity.TOP); edit.setPadding(dp(12), dp(12), dp(12), dp(12)); edit.setHorizontallyScrolling(true); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(EDIT_LIMIT)}); edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); edit.setTypeface(Typeface.MONOSPACE); edit.setText(value); return edit; }
    private View empty(GlyphDrawable.Kind kind, String title, String subtitle) { LinearLayout panel = column(); panel.setGravity(Gravity.CENTER); panel.setPadding(dp(12), dp(20), dp(12), dp(20)); TextView mark = text("", 24, PRIMARY); mark.setGravity(Gravity.CENTER); mark.setPadding(dp(17), dp(17), dp(17), dp(17)); mark.setCompoundDrawablesWithIntrinsicBounds(icon(kind, PRIMARY), null, null, null); mark.setBackground(round(SURFACE_HIGH, 22)); panel.addView(mark, new LinearLayout.LayoutParams(dp(59), dp(59))); TextView headline = text(title, 16, TEXT); headline.setGravity(Gravity.CENTER); addMargin(panel, headline, 16); TextView description = text(subtitle, 12, MUTED); description.setGravity(Gravity.CENTER); description.setLineSpacing(dp(5), 1); addMargin(panel, description, 9); return panel; }
    private void addMargin(LinearLayout parent, View child, int top) { parent.addView(child, spaced(-1, -2, top)); }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height); params.topMargin = dp(top); return params; }
    private ColorStateList navColors() { return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PRIMARY, MUTED}); }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private interface TextChange { void changed(String value); }
    private void watch(EditText edit, TextChange callback) { edit.addTextChangedListener(new TextWatcher() { @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {} @Override public void onTextChanged(CharSequence s, int start, int before, int count) { callback.changed(s.toString()); } @Override public void afterTextChanged(Editable s) {} }); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void copy(String title, String value) { ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE); clipboard.setPrimaryClip(ClipData.newPlainText(title, value)); toast("Copied to clipboard"); }
    private void onMain(Runnable action) { main.post(() -> { if (!isDestroyed()) action.run(); }); }
    private void showError(String title, Exception error) { new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message(error)).setPositiveButton(getString(R.string.common_close), null).show(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean hasError(Exchange e) { return e.status >= 400 || (e.error != null && !e.error.isEmpty()); }
    private static boolean isTunnel(Exchange e) { return "TUNNEL".equals(e.method) || "TCP".equals(e.protocol); }
    private static boolean hasHttpRequest(Exchange e) { return !isTunnel(e) && !"ERROR".equals(e.method) && (safe(e.url).startsWith("http://") || safe(e.url).startsWith("https://")) && safe(e.requestHeaders).startsWith(safe(e.method) + " "); }
    private static String connectionLabel(Exchange e) { if(isTunnel(e))return e.tls||safe(e.url).startsWith("tls://")?"TLS":"TCP";String protocol=safe(e.protocol);return protocol.startsWith("HTTP/")?protocol+(e.tls?" · TLS":""):protocol; }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String trim(String value, int limit) { if (value == null) return ""; return value.length() <= limit ? value : value.substring(0, limit) + "\n… preview truncated"; }
    private static String bytes(long value) { if (value < 1024) return value + " B"; if (value < 1024 * 1024) return String.format(Locale.US, "%.1f KB", value / 1024.0); if (value < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024)); return String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024 * 1024)); }
    private static byte[] canonicalRaw(String raw) { int split = raw.indexOf("\r\n\r\n"), separator = 4; if (split < 0) { split = raw.indexOf("\n\n"); separator = 2; } String headers = split < 0 ? raw : raw.substring(0, split), body = split < 0 ? "" : raw.substring(split + separator); return (headers.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1); }
    private static String rawRequest(Exchange e) { String headers = safe(e.requestHeaders).trim(); if (!headers.startsWith(safe(e.method) + " ")) { Uri uri = Uri.parse(safe(e.url)); String path = uri.getEncodedPath(); if (path == null || path.isEmpty()) path = "/"; if (uri.getEncodedQuery() != null) path += "?" + uri.getEncodedQuery(); headers = safe(e.method) + " " + path + " HTTP/1.1\r\n" + headers; } return headers + "\r\n\r\n" + bodyText(e.requestBody); }
    private static String rawResponse(Exchange e) { String headers = safe(e.responseHeaders).trim(); if (!headers.startsWith("HTTP/")) headers = (e.status == 0 ? "" : "HTTP/1.1 " + e.status + "\r\n") + headers; return headers + "\r\n\r\n" + bodyText(e.responseBody); }
    private static String bodyText(byte[] body) { if (body == null || body.length == 0) return ""; return new String(body, StandardCharsets.ISO_8859_1); }
    private static String toHex(byte[] bytes) { char[] hex = "0123456789abcdef".toCharArray(), out = new char[bytes.length * 2]; for (int i = 0; i < bytes.length; i++) { out[i * 2] = hex[(bytes[i] & 0xff) >>> 4]; out[i * 2 + 1] = hex[bytes[i] & 15]; } return new String(out); }
    private static byte[] fromHex(String input) { String value = input.replaceAll("\\s", ""); if ((value.length() & 1) != 0) throw new IllegalArgumentException("Hex input must have an even number of characters"); byte[] out = new byte[value.length() / 2]; for (int i = 0; i < out.length; i++) { int high = Character.digit(value.charAt(i * 2), 16), low = Character.digit(value.charAt(i * 2 + 1), 16); if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hexadecimal character"); out[i] = (byte)((high << 4) | low); } return out; }
    private static CharSequence diff(String[] a, String[] b) { int[][] lcs = new int[a.length + 1][b.length + 1]; for (int i = a.length - 1; i >= 0; i--) for (int j = b.length - 1; j >= 0; j--) lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]); SpannableStringBuilder result = new SpannableStringBuilder(); int i = 0, j = 0; while (i < a.length || j < b.length) { int start = result.length(), color; if (i < a.length && j < b.length && a[i].equals(b[j])) { result.append("  ").append(a[i++]).append('\n'); j++; color = MUTED; } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) { result.append("+ ").append(b[j++]).append('\n'); color = PRIMARY; } else { result.append("− ").append(a[i++]).append('\n'); color = ERROR; } result.setSpan(new ForegroundColorSpan(color), start, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); } return result; }
}
