package com.ccs.sprig.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.R;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.ccs.sprig.core.scanner.Finding;
import com.ccs.sprig.core.scanner.CheckRegistry;
import com.ccs.sprig.core.scanner.Progress;
import com.ccs.sprig.core.scanner.ScanConfig;
import com.ccs.sprig.core.scanner.ScanEngine;
import com.ccs.sprig.core.scanner.ScanReport;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly launched scan workspace. Opening this screen never sends a request. */
public final class ScannerActivity extends AppCompatActivity {
    public static final String EXTRA_SEED_URL="seed_url";
    public static final String EXTRA_EXCHANGE_ID="exchange_id";
    private static final int EXPORT_REPORT=841;
    private static final int BG=0xff101411,SURFACE=0xff1b211d,PRIMARY=0xffb6f3c6,ON_PRIMARY=0xff103721,
            TEXT=0xffe1e8df,MUTED=0xffa9b7ad,AMBER=0xffeecf97;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshQueued=new AtomicBoolean();
    private Session session;
    private TextInputEditText seed;
    private MaterialButton start,cancel,limits,export,checks,insertions;
    private MaterialSwitch crawl,active,stateful;
    private TextView status,summary,empty;
    private LinearProgressIndicator progress;
    private FindingsAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        session=new ViewModelProvider(this).get(Session.class);
        if(session.seedUrl==null) {
            String supplied=getIntent().getStringExtra(EXTRA_SEED_URL);
            session.seedUrl=supplied==null||supplied.length()>8192?"https://example.com/":supplied;
            long exchangeId=getIntent().getLongExtra(EXTRA_EXCHANGE_ID,0);
            if(exchangeId!=0) {
                Exchange selected=null;
                for(Exchange exchange:SprigApp.get().getStore().snapshot())if(exchange.id==exchangeId){selected=exchange;break;}
                try {
                    if(selected==null)throw new IOException(UiText.get(R.string.ui_captured_request_is_no_longer_available_open_scanner_fr_7b4c3));
                    session.capturedGetRequest=completeCapturedRequest(selected);
                    if(supplied==null)session.seedUrl=selected.url;
                }catch(IOException error){session.sourceError=error.getMessage();}
            }
        }
        LinearLayout root=column();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard=insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));return insets;
        });
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(8),dp(16),dp(8));
        MaterialButton back=outline(getString(R.string.common_close));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(-2,dp(48)));
        TextView title=text(UiText.get(R.string.ui_scanner_c7ee4),25,TEXT);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(16);header.addView(title,titleParams);
        export=outline(getString(R.string.common_export));export.setOnClickListener(v->exportReport());header.addView(export,new LinearLayout.LayoutParams(-2,dp(48)));root.addView(header);

        LinearLayout form=column();form.setPadding(dp(20),dp(6),dp(20),0);
        TextInputLayout target=field(UiText.get(R.string.ui_seed_url_8357c),false);seed=(TextInputEditText)target.getEditText();seed.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);seed.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});seed.setText(session.seedUrl);form.addView(target);
        TextView note=text(UiText.get(R.string.ui_same_origin_audit_with_explicit_budgets_run_against_sys_32199)+(session.capturedGetRequest==null?"":UiText.get(R.string.ui_captured_request_headers_are_selected_for_this_origin_d8a98)),11,MUTED);note.setLineSpacing(dp(2),1);add(form,note,8);
        LinearLayout toggles=row();toggles.setGravity(Gravity.CENTER_VERTICAL);
        crawl=new MaterialSwitch(this);crawl.setText(getString(R.string.scanner_crawl));crawl.setTextColor(TEXT);crawl.setContentDescription(getString(R.string.scanner_crawl_description));crawl.setChecked(session.crawl);
        active=new MaterialSwitch(this);active.setText(getString(R.string.scanner_active));active.setTextColor(TEXT);active.setContentDescription(getString(R.string.scanner_active_description));active.setChecked(session.active);
        toggles.addView(crawl,new LinearLayout.LayoutParams(0,-2,1));toggles.addView(active,new LinearLayout.LayoutParams(0,-2,1));add(form,toggles,6);
        stateful=new MaterialSwitch(this);stateful.setText(getString(R.string.scanner_stateful));stateful.setTextSize(12);stateful.setTextColor(AMBER);stateful.setChecked(session.allowStateful);stateful.setVisibility(session.isPost()?View.VISIBLE:View.GONE);stateful.setOnCheckedChangeListener((button,enabled)->{session.allowStateful=enabled;render();});add(form,stateful,0);
        if(session.isPost())add(form,text(UiText.get(R.string.ui_post_replays_can_change_server_state_the_original_captu_e5172),10,MUTED),3);
        LinearLayout advanced=row();checks=outline(UiText.get(R.string.ui_checks_5098d));insertions=outline(UiText.get(R.string.ui_insertion_points_a511f));checks.setOnClickListener(v->showChecks());insertions.setOnClickListener(v->showInsertions());advanced.addView(checks,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams inputsParams=new LinearLayout.LayoutParams(0,-2,1);inputsParams.leftMargin=dp(8);advanced.addView(insertions,inputsParams);add(form,advanced,4);
        limits=outline("");limits.setOnClickListener(v->showLimits());add(form,limits,4);
        LinearLayout actions=row();start=filled(UiText.get(R.string.ui_start_scan_3c6cf));cancel=outline(getString(R.string.common_cancel));start.setOnClickListener(v->startScan());cancel.setOnClickListener(v->{session.cancel();render();});
        actions.addView(start,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(-2,dp(52));stopParams.leftMargin=dp(10);actions.addView(cancel,stopParams);add(form,actions,10);
        status=text(getString(R.string.scanner_ready),13,PRIMARY);add(form,status,14);
        progress=new LinearProgressIndicator(this);progress.setIndeterminate(true);progress.setIndicatorColor(PRIMARY);add(form,progress,8);
        summary=text(getString(R.string.scanner_summary),11,MUTED);add(form,summary,10);root.addView(form);

        RecyclerView results=new RecyclerView(this);results.setLayoutManager(new LinearLayoutManager(this));results.setClipToPadding(false);results.setPadding(dp(20),dp(12),dp(20),dp(20));
        adapter=new FindingsAdapter();results.setAdapter(adapter);root.addView(results,new LinearLayout.LayoutParams(-1,Math.max(dp(160),Math.min(dp(320),getResources().getDisplayMetrics().heightPixels/4))));
        empty=text(UiText.get(R.string.ui_your_scan_results_will_appear_here_nobservations_are_se_2ba15),13,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(10),dp(24),dp(24));root.addView(empty);
        ScrollView workspace=new ScrollView(this);workspace.setFillViewport(true);workspace.setBackgroundColor(BG);workspace.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(workspace);root.requestFocus();
        session.observer=()->{
            if(refreshQueued.compareAndSet(false,true))main.postDelayed(()->{refreshQueued.set(false);if(!isDestroyed())render();},100);
        };
        render();
    }

    private void startScan() {
        if(session.running)return;
        String url=seed.getText()==null?"":seed.getText().toString().trim();
        if(!(url.startsWith("http://")||url.startsWith("https://"))){seed.setError(UiText.get(R.string.ui_use_an_http_or_https_url_f59cb));return;}
        session.seedUrl=url;session.crawl=crawl.isChecked();session.active=active.isChecked();
        session.start();render();
    }
    private void showLimits() {
        LinearLayout body=column();body.setPadding(dp(22),0,dp(22),0);
        TextInputLayout pages=number(UiText.get(R.string.ui_maximum_pages_d1fd8),session.maxPages),requests=number(UiText.get(R.string.ui_request_budget_27fc4),session.maxRequests),workers=number(UiText.get(R.string.ui_concurrent_pages_b6763),session.concurrency),delay=number(UiText.get(R.string.ui_minimum_request_interval_ms_c0097),session.delayMillis),points=number(UiText.get(R.string.ui_maximum_insertion_points_per_page_a96ae),session.maxPoints),perCheck=number(UiText.get(R.string.ui_maximum_probes_per_check_category_a0c30),session.maxPerCheck);
        body.addView(pages);add(body,requests,10);add(body,workers,10);add(body,delay,10);add(body,points,10);add(body,perCheck,10);
        TextView hint=text(UiText.get(R.string.ui_limits_1_200_pages_1_2000_requests_1_4_workers_200_6000_9330d),12,MUTED);add(body,hint,12);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_scan_limits_f185d)).setView(scroll).setPositiveButton(getString(R.string.common_save),null).setNegativeButton(getString(R.string.common_cancel),null).create();
        dialog.setOnShowListener(v->dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                int p=Integer.parseInt(pages.getEditText().getText().toString()),r=Integer.parseInt(requests.getEditText().getText().toString()),w=Integer.parseInt(workers.getEditText().getText().toString());
                long d=Long.parseLong(delay.getEditText().getText().toString());int pointsValue=Integer.parseInt(points.getEditText().getText().toString()),checkValue=Integer.parseInt(perCheck.getEditText().getText().toString());
                if(p<1||p>200||r<1||r>2000||w<1||w>4||d<200||d>60000||pointsValue<1||pointsValue>24||checkValue<1||checkValue>2000)throw new IllegalArgumentException(UiText.get(R.string.ui_values_exceed_the_supported_bounds_62f71));
                session.maxPages=p;session.maxRequests=r;session.concurrency=w;session.delayMillis=d;session.maxPoints=pointsValue;session.maxPerCheck=checkValue;dialog.dismiss();render();
            }catch(IllegalArgumentException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }
    private void showChecks(){
        List<CheckRegistry.Definition> registry=CheckRegistry.all();String[] names=new String[registry.size()];boolean[] selected=new boolean[registry.size()];Set<String> draft=new LinkedHashSet<>(session.enabled);
        for(int i=0;i<registry.size();i++){CheckRegistry.Definition check=registry.get(i);names[i]=ScannerLabels.title(check)+" · "+getString(check.active?R.string.label_active:R.string.label_passive)+"\n"+ScannerLabels.description(check);selected[i]=draft.contains(check.id);}
        new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_check_categories_f571a)).setMultiChoiceItems(names,selected,(dialog,index,checked)->{if(checked)draft.add(registry.get(index).id);else draft.remove(registry.get(index).id);})
                .setPositiveButton(getString(R.string.common_save),(dialog,which)->{session.enabled.clear();session.enabled.addAll(draft);render();})
                .setNeutralButton(UiText.get(R.string.ui_defaults_42881),(dialog,which)->{session.enabled.clear();session.enabled.addAll(CheckRegistry.defaultIds());render();}).setNegativeButton(getString(R.string.common_cancel),null).show();
    }
    private void showInsertions(){
        LinearLayout body=column();body.setPadding(dp(22),0,dp(22),dp(8));List<MaterialSwitch> switches=new ArrayList<>();
        String[] names={UiText.get(R.string.ui_url_query_parameters_2b6ce),UiText.get(R.string.ui_explicit_headers_81715),UiText.get(R.string.ui_captured_post_form_fields_b9e9b),UiText.get(R.string.ui_captured_post_json_string_leaves_7cfc1)};ScanConfig.InsertionType[] types=ScanConfig.InsertionType.values();
        for(int i=0;i<types.length;i++){MaterialSwitch toggle=new MaterialSwitch(this);toggle.setText(names[i]);toggle.setTextSize(13);toggle.setTextColor(TEXT);toggle.setChecked(session.points.contains(types[i]));body.addView(toggle);switches.add(toggle);}
        TextInputLayout headers=field(UiText.get(R.string.ui_header_names_comma_or_space_separated_bdbfe),true);headers.getEditText().setText(String.join(", ",session.headers));headers.getEditText().setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});add(body,headers,12);
        TextInputLayout callback=field(UiText.get(R.string.ui_your_collaborator_callback_url_or_hostname_be6cc),false);callback.getEditText().setText(session.callback);callback.getEditText().setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});add(body,callback,12);
        add(body,text(UiText.get(R.string.ui_header_names_are_explicit_authentication_framing_header_9801f),11,MUTED),12);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_insertion_points_a511f)).setView(scroll).setPositiveButton(getString(R.string.common_save),null).setNegativeButton(getString(R.string.common_cancel),null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            Set<String> selectedHeaders=new LinkedHashSet<>();for(String header:headers.getEditText().getText().toString().split("[\\s,]+"))if(!header.isEmpty())selectedHeaders.add(header);
            if(selectedHeaders.size()>8){headers.setError(UiText.get(R.string.ui_use_at_most_eight_header_names_1421f));return;}
            if(switches.get(1).isChecked()&&selectedHeaders.isEmpty()){headers.setError(UiText.get(R.string.ui_enter_at_least_one_explicit_header_name_69d44));return;}
            session.points.clear();for(int i=0;i<types.length;i++)if(switches.get(i).isChecked())session.points.add(types[i]);session.headers.clear();session.headers.addAll(selectedHeaders);session.callback=callback.getEditText().getText().toString().trim();dialog.dismiss();render();
        }));dialog.show();
    }
    private void render() {
        boolean busy=session.running;start.setEnabled(!busy&&session.sourceError==null&&(!session.isPost()||session.allowStateful));cancel.setEnabled(busy);limits.setEnabled(!busy);checks.setEnabled(!busy);insertions.setEnabled(!busy);stateful.setEnabled(!busy);seed.setEnabled(!busy);crawl.setEnabled(!busy);active.setEnabled(!busy);
        checks.setText(getResources().getQuantityString(R.plurals.scanner_categories,session.enabled.size(),session.enabled.size()));insertions.setText(getResources().getQuantityString(R.plurals.scanner_inputs,session.points.size(),session.points.size()));
        limits.setText(getString(R.string.scanner_limits,session.maxPages,session.maxRequests,session.concurrency,session.delayMillis));
        progress.setVisibility(busy?View.VISIBLE:View.GONE);export.setEnabled(session.report!=null&&!busy);
        ScanReport report=session.report;
        if(session.sourceError!=null||session.error!=null){status.setText(session.sourceError!=null?session.sourceError:session.error);status.setTextColor(AMBER);}
        else if(report!=null){status.setText(getString(R.string.scanner_progress,report.state,report.pages,report.requests));status.setTextColor(PRIMARY);}
        else{status.setText(busy?getString(R.string.scanner_starting):getString(R.string.scanner_ready));status.setTextColor(PRIMARY);}
        List<Finding> findings=report==null?Collections.emptyList():report.findings;
        adapter.submitList(new ArrayList<>(findings));empty.setVisibility(findings.isEmpty()?View.VISIBLE:View.GONE);
        if(report!=null){summary.setText(getString(R.string.scanner_findings,findings.size(),report.errors.size()));summary.setOnClickListener(v->{if(!report.errors.isEmpty())showText(UiText.get(R.string.ui_transport_errors_93af9),String.join("\n\n",report.errors));});}
        else{summary.setText(getString(R.string.scanner_summary));summary.setOnClickListener(null);}
    }
    private void showFinding(Finding finding) {
        String details=finding.severity+" · "+finding.confidence+"\n\n"+finding.detail+"\n\nURL\n"+finding.url+
                (finding.parameter.isEmpty()?"":UiText.get(R.string.ui_n_nparameter_n_c0188)+finding.parameter)+UiText.get(R.string.ui_n_nevidence_n_da885)+finding.evidence+
                UiText.get(R.string.ui_n_nexchange_references_n_4b319)+finding.evidenceExchangeIds;
        showText(finding.title,details);
    }
    private void showText(String title,String content) {
        TextView text=text(content,13,TEXT);text.setTextIsSelectable(true);text.setLineSpacing(dp(3),1);text.setPadding(dp(24),dp(4),dp(24),dp(16));
        ScrollView scroll=new ScrollView(this);scroll.addView(text);new MaterialAlertDialogBuilder(this).setTitle(title).setView(scroll).setPositiveButton(getString(R.string.common_close),null).show();
    }
    private void exportReport() {
        if(session.report==null||session.running)return;
        new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_export_report_0ddb7)).setItems(new String[]{"JSON","HTML","CSV"},(dialog,index)->{
            session.exportFormat=index;session.pendingExport=session.report;String extension=index==1?"html":index==2?"csv":"json",mime=index==1?"text/html":index==2?"text/csv":"application/json";
            try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,"sprig-scan-"+session.report.startedMillis+'.'+extension),EXPORT_REPORT);}
            catch(Exception error){Toast.makeText(this,UiText.get(R.string.ui_cannot_open_document_picker_2a345),Toast.LENGTH_LONG).show();}
        }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==EXPORT_REPORT&&result!=Activity.RESULT_OK){session.pendingExport=null;return;}
        if(request!=EXPORT_REPORT||result!=Activity.RESULT_OK||data==null||data.getData()==null||session.report==null)return;
        ScanReport report=session.pendingExport==null?session.report:session.pendingExport;session.pendingExport=null;int format=session.exportFormat;android.net.Uri uri=data.getData();
        session.submitIo(()->{
            try(OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){
                if(stream==null)throw new IllegalStateException(UiText.get(R.string.ui_cannot_open_selected_document_d46de));if(format==1)report.writeHtml(stream);else if(format==2)report.writeCsv(stream);else report.writeJson(stream);
                main.post(()->{if(!isDestroyed())Toast.makeText(this,UiText.get(R.string.ui_scan_report_exported_c1c15),Toast.LENGTH_SHORT).show();});
            }catch(Exception error){main.post(()->{if(!isDestroyed())Toast.makeText(this,UiText.get(R.string.ui_report_export_failed_4df27),Toast.LENGTH_LONG).show();});}
        });
    }
    @Override protected void onStop(){if(seed!=null&&seed.getText()!=null)session.seedUrl=seed.getText().toString();super.onStop();}
    @Override protected void onDestroy(){session.observer=null;main.removeCallbacksAndMessages(null);super.onDestroy();}

    private final class FindingsAdapter extends ListAdapter<Finding,FindingHolder> {
        FindingsAdapter(){super(new DiffUtil.ItemCallback<Finding>(){
            @Override public boolean areItemsTheSame(@NonNull Finding a,@NonNull Finding b){return a.id==b.id;}
            @Override public boolean areContentsTheSame(@NonNull Finding a,@NonNull Finding b){
                return a.id==b.id&&a.baselineExchangeId==b.baselineExchangeId&&a.probeExchangeId==b.probeExchangeId
                        &&a.checkId.equals(b.checkId)&&a.severity.equals(b.severity)&&a.confidence.equals(b.confidence)
                        &&a.title.equals(b.title)&&a.detail.equals(b.detail)&&a.url.equals(b.url)
                        &&a.parameter.equals(b.parameter)&&a.evidence.equals(b.evidence)&&a.evidenceExchangeIds.equals(b.evidenceExchangeIds);
            }
        });setHasStableIds(true);}
        @Override public long getItemId(int position){return getItem(position).id;}
        @NonNull @Override public FindingHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            MaterialCardView card=new MaterialCardView(ScannerActivity.this);card.setRadius(dp(18));card.setCardBackgroundColor(SURFACE);card.setCardElevation(0);
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,-2);params.bottomMargin=dp(9);card.setLayoutParams(params);
            LinearLayout body=column();body.setPadding(dp(16),dp(14),dp(16),dp(14));TextView category=text("",10,MUTED),title=text("",15,TEXT),url=text("",11,MUTED);
            category.setLetterSpacing(.04f);body.addView(category);add(body,title,7);url.setMaxLines(2);url.setEllipsize(android.text.TextUtils.TruncateAt.END);add(body,url,7);card.addView(body);
            return new FindingHolder(card,category,title,url);
        }
        @Override public void onBindViewHolder(@NonNull FindingHolder holder,int position){
            Finding finding=getItem(position);holder.category.setText(getString(R.string.raw_pair,finding.severity,finding.confidence).toUpperCase(Locale.getDefault()));
            holder.category.setTextColor("High".equals(finding.severity)?0xffffb4ab:"Medium".equals(finding.severity)?AMBER:MUTED);holder.title.setText(finding.title);holder.url.setText(finding.url);holder.itemView.setOnClickListener(v->showFinding(finding));
        }
    }
    private static final class FindingHolder extends RecyclerView.ViewHolder {
        final TextView category,title,url;FindingHolder(View view,TextView category,TextView title,TextView url){super(view);this.category=category;this.title=title;this.url=url;}
    }

    /** A retained owner keeps scans independent of Activity recreation and cancels them on final exit. */
    public static final class Session extends ViewModel {
        final ExecutorService io=UiWorkQueue.create("sprig-scan-owner",2);
        volatile Runnable observer;
        volatile boolean running;
        volatile String error;
        volatile ScanReport report;
        volatile ScanEngine engine;
        String seedUrl,sourceError;byte[] capturedGetRequest;int maxPages=20,maxRequests=150,concurrency=2,maxPoints=6,maxPerCheck=24,exportFormat;long delayMillis=250;boolean crawl=true,active=true,allowStateful;
        final Set<String> enabled=new LinkedHashSet<>(CheckRegistry.defaultIds()),headers=new LinkedHashSet<>(Collections.singletonList("X-Sprig-Input"));final Set<ScanConfig.InsertionType> points=EnumSet.of(ScanConfig.InsertionType.QUERY);String callback="";ScanReport pendingExport;
        public Session(){ }
        void start(){
            if(running||sourceError!=null||(isPost()&&!allowStateful))return;running=true;error=null;report=null;
            ScanConfig config=new ScanConfig(seedUrl,capturedGetRequest);config.maxPages=maxPages;config.maxRequests=maxRequests;config.concurrency=concurrency;config.delayMillis=delayMillis;config.crawl=crawl;config.activeChecks=active;config.allowStatefulTesting=allowStateful;config.maxInsertionPoints=maxPoints;config.maxProbesPerCheck=maxPerCheck;config.enabledChecks.clear();config.enabledChecks.addAll(enabled);config.insertionPoints.clear();config.insertionPoints.addAll(points);config.headerNames.clear();config.headerNames.addAll(headers);config.collaboratorPayload=callback;
            ScanEngine current=new ScanEngine((url,raw)->SprigApp.get().getProxy().replay(url,raw));engine=current;
            if(!submitIo(()->{
                try{report=current.run(config,new ScanEngine.Listener(){@Override public void onProgress(Progress p){report=current.snapshot();notifyObserver();}});}
                catch(Exception failure){error=failure.getMessage()==null?UiText.get(R.string.ui_scan_failed_944d2):failure.getMessage();}
                finally{running=false;notifyObserver();}
            })){current.cancel();engine=null;running=false;notifyObserver();}
        }
        boolean submitIo(Runnable task){return UiWorkQueue.submit(io,task,()->{error=SprigApp.get().getString(R.string.workspace_busy);notifyObserver();});}
        void cancel(){ScanEngine current=engine;if(current!=null)current.cancel();}
        boolean isPost(){return capturedGetRequest!=null&&capturedGetRequest.length>=5&&capturedGetRequest[0]=='P'&&capturedGetRequest[1]=='O'&&capturedGetRequest[2]=='S'&&capturedGetRequest[3]=='T'&&capturedGetRequest[4]==' ';}
        void notifyObserver(){Runnable current=observer;if(current!=null)current.run();}
        @Override protected void onCleared(){observer=null;cancel();io.shutdownNow();}
    }
    /** Validate framing, rather than trusting a possibly truncated editor representation. */
    static byte[] completeCapturedGet(Exchange exchange)throws IOException {
        if(!"GET".equalsIgnoreCase(exchange.method))throw new IOException(UiText.get(R.string.ui_scanner_requires_a_captured_get_request_57b4d));return completeCapturedRequest(exchange);
    }
    static byte[] completeCapturedRequest(Exchange exchange)throws IOException {
        if(!("GET".equalsIgnoreCase(exchange.method)||"POST".equalsIgnoreCase(exchange.method))||exchange.requestHeaders==null||exchange.requestHeaders.isEmpty())throw new IOException(UiText.get(R.string.ui_scanner_requires_a_complete_captured_get_or_opt_in_post_5b10e));
        byte[] raw=exchange.rawRequest();if(raw.length>64*1024)throw new IOException(UiText.get(R.string.ui_captured_scanner_request_exceeds_64_kib_e9910));
        ByteArrayInputStream input=new ByteArrayInputStream(raw);HttpCodec.Message parsed=HttpCodec.readRequest(input,null);
        if(parsed==null||(!"GET".equalsIgnoreCase(parsed.method())&&!"POST".equalsIgnoreCase(parsed.method()))||("GET".equalsIgnoreCase(parsed.method())&&parsed.body.length!=0)||input.available()!=0)throw new IOException(UiText.get(R.string.ui_scanner_requires_a_complete_get_or_post_request_7d604));
        return raw;
    }
    private TextInputLayout number(String hint,long value){TextInputLayout field=field(hint,false);field.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER);field.getEditText().setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});String machineNumber=Long.toString(value);field.getEditText().setText(machineNumber);return field;}
    private TextInputLayout field(String hint,boolean multiline){TextInputLayout layout=new TextInputLayout(this);layout.setHint(hint);layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);layout.setBoxCornerRadii(dp(16),dp(16),dp(16),dp(16));TextInputEditText edit=new TextInputEditText(layout.getContext());edit.setTextColor(TEXT);edit.setTextSize(14);edit.setSingleLine(!multiline);edit.setSaveEnabled(false);layout.setSaveEnabled(false);layout.addView(edit,new LinearLayout.LayoutParams(-1,-2));return layout;}
    private MaterialButton filled(String title){MaterialButton b=new MaterialButton(this);b.setText(title);b.setAllCaps(false);b.setCornerRadius(dp(16));b.setBackgroundTintList(ColorStateList.valueOf(PRIMARY));b.setTextColor(ON_PRIMARY);return b;}
    private MaterialButton outline(String title){MaterialButton b=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);b.setText(title);b.setAllCaps(false);b.setCornerRadius(dp(14));b.setTextColor(PRIMARY);return b;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(margin);parent.addView(child,params);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
