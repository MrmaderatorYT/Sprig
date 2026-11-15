package com.ccs.sprig.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.R;
import com.ccs.sprig.core.intruder.*;
import com.ccs.sprig.core.model.Exchange;
import com.ccs.sprig.core.proxy.HttpCodec;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Position-based attacks with retained drafts, explicit start and bounded live result rendering. */
public final class IntruderActivity extends AppCompatActivity {
    public static final String EXTRA_SEED_URL="seed_url",EXTRA_EXCHANGE_ID="exchange_id";
    private static final int BG=0xff101411,SURFACE=0xff1b211d,PRIMARY=0xffb6f3c6,TEXT=0xffe1e8df,MUTED=0xffa9b7ad,ERROR=0xffffb4ab;
    private static final int LIMIT=1024*1024,MAX_PAYLOAD_CHARS=2*1024*1024,IMPORT=301,EXPORT=302;
    private static final String[] TYPES={"Sniper","Battering ram","Pitchfork","Cluster bomb"};
    private static final String[] PROTOCOLS={"AUTO","HTTP/1.1","HTTP/2","HTTP/3"};
    private static String[] rules(){return new String[]{UiText.get(R.string.ui_url_encode_17f62),UiText.get(R.string.ui_url_decode_06a0a),UiText.get(R.string.ui_base64_encode_5dfdb),UiText.get(R.string.ui_base64_decode_8a239),UiText.get(R.string.ui_hex_encode_08313),UiText.get(R.string.ui_hex_decode_c223b),UiText.get(R.string.ui_uppercase_83087),UiText.get(R.string.ui_lowercase_b563f),UiText.get(R.string.ui_prefix_90ece),UiText.get(R.string.ui_suffix_885db),UiText.get(R.string.ui_replace_bytes_0add9)};}
    private static final AtomicReference<Handoff> HANDOFF=new AtomicReference<>();
    private static final class Handoff {final String url,protocol;final byte[] raw;Handoff(String url,byte[] raw,String protocol){this.url=url;this.raw=raw.clone();this.protocol=protocol;}}
    public static void prepareDraft(String url,byte[] raw,String protocol){if(raw==null||raw.length>LIMIT)throw new IllegalArgumentException(UiText.get(R.string.ui_intruder_editor_limit_is_1_mib_aaef7));HANDOFF.set(new Handoff(url,raw,protocol));}

    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshQueued=new AtomicBoolean();
    private final Runnable attackListener=()->{if(refreshQueued.compareAndSet(false,true))main.postDelayed(()->{refreshQueued.set(false);if(this.resumed&&!isDestroyed())refresh();},150);};
    private Model model;private boolean resumed;private IntruderAttack observed;
    private boolean controlsBusy;private FrameLayout content;private TabLayout tabs;private TextView status,summary;private LinearProgressIndicator progress;
    private MaterialButton control,cancel,export;private ResultAdapter resultAdapter;private int page;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);model=new ViewModelProvider(this).get(Model.class);
        if(!model.initialized){model.initialized=true;model.attack=IntruderEngine.latest();String seed=getIntent().getStringExtra(EXTRA_SEED_URL);if(seed!=null&&seed.length()<=8192)model.draft.url=seed;
            long id=getIntent().getLongExtra(EXTRA_EXCHANGE_ID,0);if(id!=0)loadCaptured(id);}
        Handoff handoff=HANDOFF.getAndSet(null);if(handoff!=null){model.draft.url=handoff.url;model.draft.raw=new String(handoff.raw,StandardCharsets.ISO_8859_1);model.draft.protocol=knownProtocol(handoff.protocol);model.sourceError="";}
        page=model.page;LinearLayout root=column();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),0,dp(16),0);MaterialButton back=button(getString(R.string.common_close),false);back.setOnClickListener(v->finish());header.addView(back);
        TextView title=text("Intruder",25,TEXT);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=dp(14);header.addView(title,tp);
        export=button(getString(R.string.common_export),false);export.setOnClickListener(v->exportResults());header.addView(export);root.addView(header);
        status=text(getString(R.string.scanner_ready),13,PRIMARY);status.setPadding(dp(20),dp(8),dp(20),dp(8));root.addView(status);
        progress=new LinearProgressIndicator(this);progress.setIndicatorColor(PRIMARY);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));
        tabs=new TabLayout(this);for(String titleText:new String[]{UiText.get(R.string.ui_positions_cd468),UiText.get(R.string.ui_payloads_b157e),UiText.get(R.string.ui_results_612e1)})tabs.addTab(tabs.newTab().setText(titleText));root.addView(tabs);
        content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        summary=text("",11,MUTED);summary.setPadding(dp(20),dp(8),dp(20),dp(2));root.addView(summary);
        LinearLayout actions=row();actions.setPadding(dp(20),dp(6),dp(20),dp(12));control=button(getString(R.string.intruder_start),true);cancel=button(getString(R.string.common_cancel),false);actions.addView(control,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(52));cp.leftMargin=dp(10);actions.addView(cancel,cp);root.addView(actions);
        control.setOnClickListener(v->control());cancel.setOnClickListener(v->model.cancel());
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){@Override public void onTabSelected(TabLayout.Tab tab){showPage(tab.getPosition());}@Override public void onTabUnselected(TabLayout.Tab tab){}@Override public void onTabReselected(TabLayout.Tab tab){}});
        setContentView(root);root.requestFocus();tabs.getTabAt(page).select();showPage(page);refresh();
    }
    private void loadCaptured(long id){
        Exchange source=null;for(Exchange e:SprigApp.get().getStore().snapshot())if(e.id==id){source=e;break;}
        try{
            if(source==null)throw new IOException(UiText.get(R.string.ui_captured_request_is_no_longer_available_create_a_new_te_e74c2));
            byte[] raw=source.rawRequest();HttpCodec.Message head=HttpCodec.readHead(new ByteArrayInputStream(raw));String length=head.header("Content-Length");
            if(length!=null&&Long.parseLong(length)>(source.requestBody==null?0:source.requestBody.length)||length==null&&source.truncated&&source.requestBody!=null&&source.requestBody.length>=512*1024)throw new IOException(UiText.get(R.string.ui_the_captured_request_body_is_incomplete_use_the_origina_e92e5));
            if(raw.length>LIMIT||!source.requestHeaders.startsWith(source.method+" "))throw new IOException(UiText.get(R.string.ui_no_complete_editable_http_request_is_available_35512));
            model.draft.url=source.url;model.draft.raw=new String(raw,StandardCharsets.ISO_8859_1);model.draft.protocol=knownProtocol(source.protocol);
        }catch(Exception e){model.sourceError=message(e);model.draft.raw="";}
    }
    @Override protected void onStart(){super.onStart();resumed=true;model.observer=()->main.post(()->{if(resumed&&!isDestroyed())refresh();});refresh();}
    @Override protected void onStop(){resumed=false;detach();model.observer=null;super.onStop();}
    @Override protected void onDestroy(){detach();main.removeCallbacksAndMessages(null);super.onDestroy();}
    private void detach(){if(observed!=null){observed.removeListener(attackListener);observed=null;}}
    private void showPage(int next){page=next;model.page=next;controlsBusy=busy();resultAdapter=null;content.removeAllViews();content.addView(next==0?positions():next==1?payloads():results(),new FrameLayout.LayoutParams(-1,-1));refresh();}

    private View positions(){
        LinearLayout body=body();Draft d=model.draft;boolean editable=!busy();
        if(!model.sourceError.isEmpty())add(body,text(model.sourceError,12,ERROR),0);
        TextInputLayout target=field(UiText.get(R.string.ui_target_url_b7d8a),d.url,1,8192,false);watch(target,v->d.url=v);target.setEnabled(editable);body.addView(target);
        Spinner protocol=spinner(PROTOCOLS,indexOf(PROTOCOLS,d.protocol),i->d.protocol=PROTOCOLS[i]);protocol.setEnabled(editable);add(body,labelled(UiText.get(R.string.ui_wire_protocol_44d80),protocol),12);
        TextView explanation=text(modeExplanation(d.type),12,MUTED);Spinner type=spinner(TYPES,d.type,i->{if(d.type!=i){d.type=i;explanation.setText(modeExplanation(i));}});type.setEnabled(editable);add(body,labelled(UiText.get(R.string.ui_attack_type_feafe),type),10);
        add(body,explanation,8);
        TextInputLayout request=field(UiText.get(R.string.ui_raw_request_position_pairs_dd273),d.raw,9,LIMIT,true);TextInputEditText editor=(TextInputEditText)request.getEditText();editor.setHorizontallyScrolling(true);watch(request,v->{d.raw=v;model.sourceError="";});editor.setEnabled(editable);add(body,request,16);
        LinearLayout markers=row();MaterialButton mark=button(UiText.get(R.string.ui_mark_selection_ac51f),false),clear=button(UiText.get(R.string.ui_clear_markers_1b488),false);markers.addView(mark,new LinearLayout.LayoutParams(0,dp(48),1));markers.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));mark.setEnabled(editable);clear.setEnabled(editable);
        mark.setOnClickListener(v->{int start=Math.max(0,editor.getSelectionStart()),end=Math.max(start,editor.getSelectionEnd());Editable value=editor.getText();value.insert(end,"§");value.insert(start,"§");});clear.setOnClickListener(v->editor.setText(editor.getText().toString().replace("§","")));add(body,markers,4);
        MaterialButton auto=button(UiText.get(R.string.ui_mark_query_values_415ee),false);auto.setEnabled(editable);auto.setOnClickListener(v->{String raw=editor.getText().toString();int line=raw.indexOf('\n');String first=line<0?raw:raw.substring(0,line);java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("([?&][^=&#\\s]+)=([^&#\\s]*)").matcher(first.replace("§",""));StringBuffer out=new StringBuffer();while(matcher.find())matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(matcher.group(1)+"=§"+matcher.group(2)+"§"));matcher.appendTail(out);String marked=out+(line<0?"":raw.substring(line));editor.setText(marked);});add(body,auto,4);
        add(body,text(UiText.get(R.string.ui_sniper_and_battering_ram_use_one_set_pitchfork_and_clus_b378e),11,MUTED),12);
        MaterialSwitch host=new MaterialSwitch(this);host.setText(getString(R.string.intruder_preserve_host));host.setTextColor(TEXT);host.setChecked(d.preserveHost);host.setEnabled(editable);host.setOnCheckedChangeListener((v,value)->d.preserveHost=value);add(body,host,12);
        add(body,text(UiText.get(R.string.ui_when_enabled_only_the_http_host_authority_changes_the_c_83e06),11,MUTED),6);
        MaterialButton limits=button(UiText.get(R.string.ui_rate_limits_and_matching_f184c),false);limits.setEnabled(editable);limits.setOnClickListener(v->options());add(body,limits,16);
        MaterialButton preview=button(UiText.get(R.string.ui_validate_and_preview_count_c3b69),false);preview.setEnabled(editable);preview.setOnClickListener(v->model.validate());add(body,preview,6);return scroll(body);
    }
    private View payloads(){
        LinearLayout body=body();Draft draft=model.draft;model.selectedSet=Math.min(model.selectedSet,draft.sets.size()-1);int selected=model.selectedSet;SetDraft set=draft.sets.get(selected);boolean editable=!busy();
        String[] names=new String[draft.sets.size()];for(int i=0;i<names.length;i++)names[i]=getString(R.string.payload_set_number,i+1);
        Spinner selection=spinner(names,selected,i->{if(model.selectedSet!=i){model.selectedSet=i;showPage(1);}});add(body,selection,0);
        LinearLayout sets=row();MaterialButton plus=button(UiText.get(R.string.ui_add_set_6e0bd),false),minus=button(UiText.get(R.string.ui_remove_set_82dbf),false);sets.addView(plus,new LinearLayout.LayoutParams(0,dp(48),1));sets.addView(minus,new LinearLayout.LayoutParams(0,dp(48),1));plus.setEnabled(editable&&draft.sets.size()<32);minus.setEnabled(editable&&draft.sets.size()>1);plus.setOnClickListener(v->{draft.sets.add(new SetDraft());model.selectedSet=draft.sets.size()-1;showPage(1);});minus.setOnClickListener(v->{draft.sets.remove(selected);model.selectedSet=Math.max(0,selected-1);showPage(1);});add(body,sets,6);
        Spinner source=spinner(new String[]{UiText.get(R.string.ui_text_list_utf_8_056a9),UiText.get(R.string.ui_hex_byte_list_40f3c),UiText.get(R.string.ui_numbers_7e538)},set.kind,i->{if(set.kind!=i){set.kind=i;showPage(1);}});source.setEnabled(editable);add(body,labelled(UiText.get(R.string.ui_payload_source_d9e7a),source),16);
        if(set.kind<2){int other=0;for(int i=0;i<draft.sets.size();i++)if(i!=selected)other+=draft.sets.get(i).values.length();int available=Math.max(0,Math.min(LIMIT,MAX_PAYLOAD_CHARS-other));TextInputLayout values=field(set.kind==0?UiText.get(R.string.ui_one_payload_per_line_297cb):UiText.get(R.string.ui_one_hex_payload_per_line_49b78),set.values,8,available,true);watch(values,v->set.values=v);values.getEditText().setEnabled(editable);add(body,values,12);
            add(body,text(UiText.get(R.string.ui_blank_lines_are_empty_payloads_text_lists_use_utf_8_hex_bb2e9),11,MUTED),6);MaterialButton load=button(UiText.get(R.string.ui_import_utf_8_list_file_9bf50),false);load.setEnabled(editable);load.setOnClickListener(v->{model.importSet=selected;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"),IMPORT);});add(body,load,10);
        }else{TextInputLayout from=field(UiText.get(R.string.ui_from_inclusive_324f5),set.from,1,22,false),to=field(UiText.get(R.string.ui_to_inclusive_fa931),set.to,1,22,false),step=field(UiText.get(R.string.ui_step_dc416),set.step,1,22,false),pad=field(UiText.get(R.string.ui_minimum_digits_94945),set.padding,1,2,false);for(TextInputLayout f:new TextInputLayout[]{from,to,step,pad}){f.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);f.getEditText().setEnabled(editable);add(body,f,10);}watch(from,v->set.from=v);watch(to,v->set.to=v);watch(step,v->set.step=v);watch(pad,v->set.padding=v);}
        add(body,text(UiText.get(R.string.ui_processing_rules_this_set_1f241),18,TEXT),22);
        for(int i=0;i<set.rules.size();i++){final int at=i;Rule rule=set.rules.get(i);MaterialButton item=button((i+1)+". "+rule.label(),false);item.setEnabled(editable);item.setOnClickListener(v->manageRule(set,at));add(body,item,4);}
        MaterialButton rule=button(UiText.get(R.string.ui_add_processing_rule_a5a6a),false);rule.setEnabled(editable&&set.rules.size()<16);rule.setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_add_rule_11cc2)).setItems(rules(),(dialog,index)->editRule(set,-1,new Rule(index,"",""))).show());add(body,rule,8);
        add(body,text(UiText.get(R.string.ui_rules_run_from_top_to_bottom_each_set_has_its_own_pipel_4257c),11,MUTED),10);return scroll(body);
    }
    private void manageRule(SetDraft set,int index){new MaterialAlertDialogBuilder(this).setTitle(set.rules.get(index).label()).setItems(new String[]{UiText.get(R.string.ui_edit_53016),UiText.get(R.string.ui_move_up_b4f57),UiText.get(R.string.ui_move_down_260ff),UiText.get(R.string.ui_remove_e9639)},(dialog,choice)->{if(choice==0){editRule(set,index,set.rules.get(index));return;}if(choice==1&&index>0)Collections.swap(set.rules,index,index-1);if(choice==2&&index+1<set.rules.size())Collections.swap(set.rules,index,index+1);if(choice==3)set.rules.remove(index);showPage(1);}).show();}
    private void editRule(SetDraft set,int index,Rule original){
        if(original.kind<8){if(index<0)set.rules.add(original);showPage(1);return;}
        LinearLayout form=body();TextInputLayout argument=field(original.kind==10?UiText.get(R.string.ui_find_text_utf_8_2cfcc):UiText.get(R.string.ui_text_utf_8_bbc80),original.argument,2,4096,false);form.addView(argument);TextInputLayout replacement=field(UiText.get(R.string.ui_replacement_utf_8_e1979),original.replacement,2,4096,false);if(original.kind==10)add(form,replacement,10);
        new MaterialAlertDialogBuilder(this).setTitle(rules()[original.kind]).setView(scroll(form)).setPositiveButton(getString(R.string.common_save),(dialog,which)->{Rule rule=new Rule(original.kind,argument.getEditText().getText().toString(),replacement.getEditText().getText().toString());if(rule.kind==10&&rule.argument.isEmpty()){toast(UiText.get(R.string.ui_find_text_cannot_be_empty_354f3));return;}if(index<0)set.rules.add(rule);else set.rules.set(index,rule);showPage(1);}).setNegativeButton(getString(R.string.common_cancel),null).show();
    }
    private View results(){
        LinearLayout body=column();body.setPadding(dp(20),dp(12),dp(20),0);TextInputLayout search=field(UiText.get(R.string.ui_search_payload_extract_status_or_error_ea1fc),model.query,1,200,false);watch(search,v->{model.query=v;renderResults();});body.addView(search);
        LinearLayout filters=row();filters.addView(spinner(new String[]{UiText.get(R.string.ui_all_results_96f34),UiText.get(R.string.ui_matches_ee2db),getString(R.string.filter_errors)},model.filter,i->{model.filter=i;renderResults();}),new LinearLayout.LayoutParams(0,dp(48),1));filters.addView(spinner(new String[]{getString(R.string.result_order_request),UiText.get(R.string.ui_status_bae7d),UiText.get(R.string.ui_length_3bade),UiText.get(R.string.ui_time_6c82e)},model.sort,i->{model.sort=i;renderResults();}),new LinearLayout.LayoutParams(0,dp(48),1));add(body,filters,8);
        RecyclerView list=new RecyclerView(this);list.setLayoutManager(new LinearLayoutManager(this));resultAdapter=new ResultAdapter();list.setAdapter(resultAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));return body;
    }
    private void options(){
        Draft d=model.draft;LinearLayout form=body();String[] labels={UiText.get(R.string.ui_request_budget_including_retries_d423c),UiText.get(R.string.ui_parallel_requests_fee95),UiText.get(R.string.ui_minimum_interval_ms_f8e80),UiText.get(R.string.ui_timeout_per_request_ms_db5a9),UiText.get(R.string.ui_retries_for_idempotent_methods_d419e),UiText.get(R.string.ui_retained_result_count_3d46a),UiText.get(R.string.ui_evidence_bytes_per_message_006d6)};String[] values={""+d.budget,""+d.concurrency,""+d.interval,""+d.timeout,""+d.retries,""+d.retained,""+d.evidence};List<TextInputLayout> fields=new ArrayList<>();for(int i=0;i<labels.length;i++){TextInputLayout field=field(labels[i],values[i],1,9,false);field.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER);add(form,field,8);fields.add(field);}
        TextInputLayout grep=field(UiText.get(R.string.ui_grep_literals_one_per_line_5ce45),d.matches,4,2048,false),extract=field(UiText.get(R.string.ui_grep_extract_re2_pattern_first_capture_group_af7e8),d.extract,2,512,true);add(form,grep,14);add(form,extract,10);add(form,text(UiText.get(R.string.ui_results_and_evidence_use_a_bounded_memory_budget_json_c_08cfa),11,MUTED),12);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_attack_options_78099)).setView(scroll(form)).setPositiveButton(getString(R.string.common_save),null).setNegativeButton(getString(R.string.common_cancel),null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{try{long budget=Long.parseLong(value(fields.get(0))),interval=Long.parseLong(value(fields.get(2))),timeout=Long.parseLong(value(fields.get(3)));int concurrency=Integer.parseInt(value(fields.get(1))),retries=Integer.parseInt(value(fields.get(4))),retained=Integer.parseInt(value(fields.get(5))),evidence=Integer.parseInt(value(fields.get(6)));if(budget<1||budget>100000||concurrency<1||concurrency>4||interval<0||interval>60000||timeout<1000||timeout>120000||retries<0||retries>2||retained<1||retained>10000||evidence<0||evidence>32768)throw new IllegalArgumentException(UiText.get(R.string.ui_values_exceed_supported_bounds_74bc8));d.budget=budget;d.concurrency=concurrency;d.interval=interval;d.timeout=timeout;d.retries=retries;d.retained=retained;d.evidence=evidence;d.matches=value(grep);d.extract=value(extract);dialog.dismiss();model.message=UiText.get(R.string.ui_options_saved_validate_to_preview_combinations_58d31);refresh();}catch(IllegalArgumentException error){toast(message(error));}}));dialog.show();
    }
    private void control(){
        IntruderAttack attack=model.attack;if(attack!=null&&!attack.isTerminal()){if(attack.state()==IntruderAttack.State.PAUSED)attack.resume();else if(attack.state()==IntruderAttack.State.RUNNING)attack.pause();return;}
        if(!model.sourceError.isEmpty()){toast(model.sourceError);return;}model.start();
    }
    private boolean busy(){return model.starting||model.attack!=null&&!model.attack.isTerminal();}
    private void refresh(){
        if(content==null)return;boolean nowBusy=busy();if(page!=2&&controlsBusy!=nowBusy){controlsBusy=nowBusy;content.removeAllViews();content.addView(page==0?positions():payloads(),new FrameLayout.LayoutParams(-1,-1));}IntruderAttack attack=model.attack;if(resumed&&observed!=attack){detach();observed=attack;if(attack!=null)attack.addListener(attackListener);}
        if(attack==null){status.setText(model.starting?getString(R.string.intruder_preparing):getString(R.string.scanner_ready));summary.setText(model.message.isEmpty()?UiText.get(R.string.ui_mark_positions_choose_payloads_then_start_explicitly_ca49e):model.message);progress.setVisibility(View.GONE);control.setText(model.starting?getString(R.string.intruder_preparing_short):getString(R.string.intruder_start));control.setEnabled(!model.starting);cancel.setEnabled(model.starting);export.setEnabled(false);}
        else{IntruderAttack.Stats stats=attack.stats();status.setText(getString(R.string.intruder_progress,attack.state(),stats.completed,stats.total,stats.active));summary.setText(model.starting?getString(R.string.intruder_preparing):!model.message.isEmpty()?model.message:getString(R.string.intruder_stats,stats.dispatched,stats.errors,attack.results().size(),stats.evicted,stats.message.isEmpty()?"":"\n"+stats.message));progress.setVisibility(View.VISIBLE);progress.setMax((int)Math.max(1,stats.total));progress.setProgressCompat((int)Math.min(stats.completed,stats.total),false);control.setText(attack.isTerminal()?getString(R.string.intruder_start_new):attack.state()==IntruderAttack.State.PAUSED?getString(R.string.intruder_resume):getString(R.string.intruder_pause));control.setEnabled(!model.starting&&(attack.isTerminal()||attack.state()==IntruderAttack.State.PAUSED||attack.state()==IntruderAttack.State.RUNNING));cancel.setEnabled(!attack.isTerminal()||model.starting);export.setEnabled(!attack.results().isEmpty());}
        renderResults();
    }
    private void renderResults(){
        if(resultAdapter==null)return;List<IntruderResult> rows=new ArrayList<>();if(model.attack!=null)for(IntruderResult result:model.attack.results()){if(model.filter==1&&!result.matched)continue;if(model.filter==2&&result.error.isEmpty()&&result.status<400)continue;String haystack=(result.payloads+" "+result.extract+" "+result.error+" "+result.status).toLowerCase(Locale.ROOT);if(!haystack.contains(model.query.toLowerCase(Locale.ROOT)))continue;rows.add(result);}
        Comparator<IntruderResult> order=model.sort==1?Comparator.comparingInt(r->r.status):model.sort==2?Comparator.comparingLong(r->r.responseLength):model.sort==3?Comparator.comparingLong(r->r.durationMillis):Comparator.comparingLong(r->r.number);rows.sort(order.reversed());resultAdapter.submitList(rows);
    }
    private void detail(IntruderResult result){
        IntruderAttack attack=model.attack;if(attack==null)return;LinearLayout body=body();add(body,text(getString(R.string.request_detail_heading,result.number,result.status,result.durationMillis,result.attempts),13,PRIMARY),0);add(body,text(UiText.get(R.string.ui_payloads_13147)+String.join(" | ",result.payloads)+UiText.get(R.string.ui_nextract_b0749)+result.extract+(result.error.isEmpty()?"":UiText.get(R.string.ui_nerror_6e0a9)+result.error),12,TEXT),8);
        if(result.evidenceTruncated||result.grepTruncated)add(body,text(UiText.get(R.string.ui_evidence_or_grep_input_was_shortened_by_the_configured_80deb),11,MUTED),8);
        TextView wire=text(new String(result.requestEvidence,StandardCharsets.ISO_8859_1),11,TEXT);wire.setTypeface(Typeface.MONOSPACE);wire.setTextIsSelectable(true);Spinner side=spinner(new String[]{UiText.get(R.string.ui_request_evidence_ec610),UiText.get(R.string.ui_response_evidence_2448e)},0,i->wire.setText(new String(i==0?result.requestEvidence:result.responseEvidence,StandardCharsets.ISO_8859_1)));add(body,side,10);add(body,wire,8);
        new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_intruder_result_26a49)).setView(scroll(body)).setPositiveButton(getString(R.string.nav_repeater),(dialog,which)->{try{MainActivity.openRepeater(this,attack.config.url,attack.requestForResult(result.number),attack.config.protocol);}catch(Exception error){toast(message(error));}}).setNegativeButton(getString(R.string.common_close),null).show();
    }
    private void exportResults(){if(model.attack==null)return;new MaterialAlertDialogBuilder(this).setTitle(UiText.get(R.string.ui_export_retained_results_c962a)).setItems(new String[]{UiText.get(R.string.ui_json_evidence_and_statistics_5e545),UiText.get(R.string.ui_csv_result_table_45d52)},(dialog,index)->{model.exportAttackId=model.attack.id;model.exportFormat=index;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(index==0?"application/json":"text/csv").putExtra(Intent.EXTRA_TITLE,"sprig-intruder-"+model.attack.id+(index==0?".json":".csv")),EXPORT);}).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==IMPORT){int selected=model.importSet;model.submitIo(()->{try(InputStream stream=getContentResolver().openInputStream(uri)){if(stream==null)throw new IOException(UiText.get(R.string.ui_cannot_read_payload_file_3e2f8));ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1){if(bytes.size()+count>LIMIT)throw new IOException(UiText.get(R.string.ui_payload_file_exceeds_1_mib_9d538));bytes.write(buffer,0,count);}String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);if(text.startsWith("\ufeff"))text=text.substring(1);String imported=text;main.post(()->{if(isDestroyed()||busy()||selected>=model.draft.sets.size())return;int other=0;for(int i=0;i<model.draft.sets.size();i++)if(i!=selected)other+=model.draft.sets.get(i).values.length();if(other+imported.length()>MAX_PAYLOAD_CHARS){toast(UiText.get(R.string.ui_combined_payload_drafts_exceed_the_memory_limit_8aeb1));return;}model.draft.sets.get(selected).values=imported;showPage(1);toast(UiText.get(R.string.ui_payload_list_imported_20e17));});}catch(Exception error){postToast(message(error));}});}
        if(request==EXPORT){IntruderAttack attack=IntruderEngine.find(model.exportAttackId);if(attack==null){toast(UiText.get(R.string.ui_attack_results_are_no_longer_retained_cd388));return;}int format=model.exportFormat;model.submitIo(()->{try(OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){if(stream==null)throw new IOException(UiText.get(R.string.ui_cannot_open_result_file_6ffe1));if(format==0)IntruderExporter.writeJson(attack,stream);else IntruderExporter.writeCsv(attack,stream);postToast(UiText.get(R.string.ui_retained_results_exported_68664));}catch(Exception error){postToast(message(error));}});}
    }

    public static final class Model extends ViewModel {
        final ExecutorService io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{Thread thread=new Thread(r,"sprig-intruder-ui");thread.setDaemon(true);return thread;});
        final Draft draft=new Draft();volatile IntruderAttack attack;volatile Runnable observer;volatile String message="",sourceError="";volatile boolean starting;
        AtomicBoolean abortStart=new AtomicBoolean();boolean initialized;int page,selectedSet,filter,sort,importSet,exportFormat;long exportAttackId;String query="";
        void start(){if(starting||!sourceError.isEmpty())return;Draft snapshot=new Draft(draft);AtomicBoolean aborted=new AtomicBoolean();abortStart=aborted;starting=true;message="";notifyView();if(!submitIo(()->{try{if(aborted.get()||Thread.currentThread().isInterrupted())throw new IOException(UiText.get(R.string.ui_attack_startup_cancelled_d9fba));IntruderConfig config=snapshot.config();if(aborted.get()||Thread.currentThread().isInterrupted())throw new IOException(UiText.get(R.string.ui_attack_startup_cancelled_d9fba));com.ccs.sprig.core.proxy.ProxyServer proxy=SprigApp.get().getProxy();attack=IntruderEngine.start(proxy,config);if(aborted.get()||Thread.currentThread().isInterrupted())attack.cancel();message="";}catch(Exception error){message=IntruderActivity.message(error);}finally{starting=false;notifyView();}})){starting=false;notifyView();}}
        void cancel(){abortStart.set(true);IntruderAttack current=attack;if(current!=null)current.cancel();if(starting)message=UiText.get(R.string.ui_cancelling_startup_1d6f9);notifyView();}
        void validate(){Draft snapshot=new Draft(draft);submitIo(()->{try{IntruderConfig config=snapshot.config();IntruderPlan plan=new IntruderPlan(config);message=UiText.get(R.string.intruder_plan_count,plan.positions(),plan.combinationCount(),plan.requestCount());}catch(Exception error){message=IntruderActivity.message(error);}notifyView();});}
        boolean submitIo(Runnable job){try{io.execute(job);return true;}catch(RejectedExecutionException error){message=UiText.get(R.string.ui_workspace_is_busy_wait_for_the_current_import_export_or_86779);notifyView();return false;}}
        void notifyView(){Runnable callback=observer;if(callback!=null)callback.run();}
        @Override protected void onCleared(){observer=null;abortStart.set(true);if(starting&&attack!=null)attack.cancel();io.shutdownNow();}
    }
    private static final class Draft {
        String url="https://example.com/",raw="GET /?item=§example§ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n",protocol="AUTO",matches="",extract="";
        int type,concurrency=2,retries,retained=5000,evidence=4096;long budget=1000,interval=200,timeout=30000;boolean preserveHost;final List<SetDraft> sets=new ArrayList<>();
        Draft(){sets.add(new SetDraft());}Draft(Draft source){url=source.url;raw=source.raw;protocol=source.protocol;matches=source.matches;extract=source.extract;type=source.type;concurrency=source.concurrency;retries=source.retries;retained=source.retained;evidence=source.evidence;budget=source.budget;interval=source.interval;timeout=source.timeout;preserveHost=source.preserveHost;for(SetDraft set:source.sets)sets.add(new SetDraft(set));}
        IntruderConfig config(){
            List<PayloadSource> sources=new ArrayList<>();long retainedBytes=0;for(SetDraft set:sets){PayloadSource source=set.source();retainedBytes+=source.estimatedBytes();if(retainedBytes>8L*1024*1024)throw new IllegalArgumentException(UiText.get(R.string.ui_combined_payload_sources_exceed_the_8_mib_memory_estima_ae229));sources.add(source);}
            List<String> grep=new ArrayList<>();if(!matches.isEmpty())grep.addAll(Arrays.asList(matches.split("\\r?\\n",-1)));
            IntruderConfig.Builder builder=new IntruderConfig.Builder(url.trim(),rawBytes(raw)).type(AttackType.values()[type]).protocol(protocol).payloadSets(sources).requestBudget(budget).concurrency(concurrency).intervalMillis(interval).timeoutMillis(timeout).retries(retries).resultLimit(retained).memoryBytes(Math.max(1024*1024,Math.min(16L*1024*1024,Runtime.getRuntime().maxMemory()/24))).evidenceBytes(evidence).grepMatches(grep).grepExtract(extract).preserveHost(preserveHost);
            for(int i=0;i<sets.size();i++){List<PayloadProcessor> pipeline=new ArrayList<>();for(Rule rule:sets.get(i).rules)pipeline.add(rule.processor());builder.processorsForSet(i,pipeline);}return builder.build();
        }
    }
    private static final class SetDraft {int kind;String values="alpha\nbeta",from="0",to="10",step="1",padding="0";final List<Rule> rules=new ArrayList<>();SetDraft(){}SetDraft(SetDraft source){kind=source.kind;values=source.values;from=source.from;to=source.to;step=source.step;padding=source.padding;rules.addAll(source.rules);}PayloadSource source(){return kind==0?PayloadSource.ofLines(values):kind==1?PayloadSource.ofHexLines(values):PayloadSource.numeric(Long.parseLong(from),Long.parseLong(to),Long.parseLong(step),Integer.parseInt(padding));}}
    private static final class Rule {final int kind;final String argument,replacement;Rule(int kind,String argument,String replacement){this.kind=kind;this.argument=argument;this.replacement=replacement;}String label(){return rules()[kind]+(argument.isEmpty()?"":": "+argument.substring(0,Math.min(40,argument.length())));}PayloadProcessor processor(){switch(kind){case 0:return PayloadProcessors.urlEncode();case 1:return PayloadProcessors.urlDecode();case 2:return PayloadProcessors.base64Encode();case 3:return PayloadProcessors.base64Decode();case 4:return PayloadProcessors.hexEncode();case 5:return PayloadProcessors.hexDecode();case 6:return PayloadProcessors.upperCase();case 7:return PayloadProcessors.lowerCase();case 8:return PayloadProcessors.prefix(argument.getBytes(StandardCharsets.UTF_8));case 9:return PayloadProcessors.suffix(argument.getBytes(StandardCharsets.UTF_8));default:return PayloadProcessors.replace(argument.getBytes(StandardCharsets.UTF_8),replacement.getBytes(StandardCharsets.UTF_8));}}}
    private final class ResultAdapter extends ListAdapter<IntruderResult,ResultHolder>{ResultAdapter(){super(new DiffUtil.ItemCallback<IntruderResult>(){@Override public boolean areItemsTheSame(@NonNull IntruderResult a,@NonNull IntruderResult b){return a.number==b.number;}@Override public boolean areContentsTheSame(@NonNull IntruderResult a,@NonNull IntruderResult b){return a.number==b.number&&a.matched==b.matched&&a.evidenceTruncated==b.evidenceTruncated&&a.responseLengthExact==b.responseLengthExact&&a.attempts==b.attempts&&a.status==b.status&&a.durationMillis==b.durationMillis&&a.responseLength==b.responseLength&&a.error.equals(b.error)&&a.extract.equals(b.extract)&&a.payloads.equals(b.payloads);}});}
        @NonNull @Override public ResultHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){MaterialCardView card=new MaterialCardView(parent.getContext());card.setRadius(dp(18));card.setCardBackgroundColor(SURFACE);card.setStrokeWidth(0);card.setCardElevation(0);LinearLayout box=column();box.setPadding(dp(14),dp(12),dp(14),dp(12));TextView first=text("",13,PRIMARY),second=text("",12,TEXT),third=text("",10,MUTED);box.addView(first);add(box,second,6);add(box,third,6);card.addView(box);RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(-1,-2);p.bottomMargin=dp(8);card.setLayoutParams(p);return new ResultHolder(card,first,second,third);}
        @Override public void onBindViewHolder(@NonNull ResultHolder holder,int position){IntruderResult row=getItem(position);holder.first.setText(getString(R.string.intruder_row,row.number,row.status==0?"ERR":String.valueOf(row.status),row.durationMillis,row.responseLengthExact?"":"≈",row.responseLength,row.matched?getString(R.string.intruder_match):""));holder.first.setTextColor(row.error.isEmpty()&&row.status<400?PRIMARY:ERROR);holder.second.setText(String.join(" | ",row.payloads));holder.second.setMaxLines(2);holder.third.setText(!row.error.isEmpty()?row.error:!row.extract.isEmpty()?row.extract:getResources().getQuantityString(R.plurals.intruder_attempts,row.attempts,row.attempts)+(row.evidenceTruncated?getString(R.string.intruder_limited):""));holder.third.setMaxLines(2);holder.itemView.setOnClickListener(v->detail(row));}}
    private static final class ResultHolder extends RecyclerView.ViewHolder {final TextView first,second,third;ResultHolder(View view,TextView first,TextView second,TextView third){super(view);this.first=first;this.second=second;this.third=third;}}
    private static byte[] rawBytes(String raw){int split=raw.indexOf("\r\n\r\n"),separator=4;if(split<0){split=raw.indexOf("\n\n");separator=2;}String head=split<0?raw:raw.substring(0,split),body=split<0?"":raw.substring(split+separator);return(head.replace("\r\n","\n").replace("\n","\r\n")+"\r\n\r\n"+body).getBytes(StandardCharsets.ISO_8859_1);}
    private static String knownProtocol(String value){for(String protocol:PROTOCOLS)if(protocol.equals(value))return value;return "AUTO";}
    private static int indexOf(String[] values,String target){for(int i=0;i<values.length;i++)if(values[i].equals(target))return i;return 0;}
    private static String modeExplanation(int type){return type==0?UiText.get(R.string.ui_sniper_changes_one_marked_position_at_a_time_076ca):type==1?UiText.get(R.string.ui_battering_ram_uses_the_same_payload_in_every_marked_pos_f9601):type==2?UiText.get(R.string.ui_pitchfork_advances_all_payload_sets_together_stopping_a_4c27e):UiText.get(R.string.ui_cluster_bomb_enumerates_combinations_lazily_the_request_2b9e0);}
    private interface TextChange {void changed(String value);}private interface Selection {void selected(int value);}
    private void watch(TextInputLayout field,TextChange change){field.getEditText().addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}@Override public void onTextChanged(CharSequence s,int start,int before,int count){change.changed(s.toString());}@Override public void afterTextChanged(Editable e){}});}
    private Spinner spinner(String[] values,int selected,Selection listener){Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));spinner.setSelection(selected);spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){listener.selected(position);}@Override public void onNothingSelected(android.widget.AdapterView<?> p){}});return spinner;}
    private View labelled(String title,View child){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.addView(text(title,12,MUTED),new LinearLayout.LayoutParams(0,-2,1));line.addView(child);return line;}
    private TextInputLayout field(String hint,String value,int lines,int max,boolean mono){TextInputLayout field=new TextInputLayout(this,null,com.google.android.material.R.attr.textInputOutlinedStyle);field.setHint(hint);field.setSaveEnabled(false);TextInputEditText edit=new TextInputEditText(field.getContext());edit.setSaveEnabled(false);edit.setTextSize(mono?12:14);edit.setInputType(InputType.TYPE_CLASS_TEXT|(lines>1?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0)|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setMinLines(lines);edit.setMaxLines(Math.max(lines,12));if(lines==1)edit.setSingleLine(true);if(mono)edit.setTypeface(Typeface.MONOSPACE);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});edit.setText(value);field.addView(edit,new LinearLayout.LayoutParams(-1,-2));return field;}
    private static String value(TextInputLayout field){return field.getEditText().getText().toString();}
    private LinearLayout body(){LinearLayout body=column();body.setPadding(dp(20),dp(14),dp(20),dp(28));return body;}
    private ScrollView scroll(LinearLayout child){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(child,new ScrollView.LayoutParams(-1,-2));return scroll;}
    private MaterialButton button(String title,boolean filled){MaterialButton button=new MaterialButton(this,null,filled?com.google.android.material.R.attr.materialButtonStyle:com.google.android.material.R.attr.materialButtonOutlinedStyle);button.setText(title);button.setAllCaps(false);button.setCornerRadius(dp(18));return button;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(2),1);return view;}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);return view;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);parent.addView(child,params);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}private void postToast(String text){main.post(()->{if(!isDestroyed())toast(text);});}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
