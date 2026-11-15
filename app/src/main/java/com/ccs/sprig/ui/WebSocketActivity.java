package com.ccs.sprig.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.DiffUtil;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.R;
import com.ccs.sprig.core.websocket.WebSocketHub;
import com.ccs.sprig.core.websocket.WebSocketInterceptor;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Live WebSocket inspection and replay; no frame/network work runs on Android's main thread. */
public final class WebSocketActivity extends AppCompatActivity {
    private static final int BACKGROUND=0xff101411,SURFACE=0xff1b211d,PRIMARY=0xffb6f3c6,TEXT=0xffe1e8df,MUTED=0xffa9b7ad;
    private static final int UI_EDIT_BYTES=256*1024;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->new Thread(r,"sprig-websocket-ui"));
    private final AtomicBoolean refreshQueued=new AtomicBoolean();
    private final Runnable listener=()->{if(refreshQueued.compareAndSet(false,true))main.postDelayed(()->{refreshQueued.set(false);if(this.resumed)refresh();},150);};
    private volatile WebSocketHub hub;
    private boolean resumed,changingSelection,loading=true;
    private long selected;
    private List<WebSocketHub.Session> sessions=Collections.emptyList();
    private List<String> sessionLabels=Collections.emptyList();
    private Spinner sessionPicker,mode;
    private TextInputEditText url,composer;
    private TextView status,pending;
    private MaterialButton connect,send,disconnect;
    private MaterialSwitch hold;
    private MessageAdapter adapter;
    private String extraHeaders="";
    @Override protected void onCreate(Bundle state){super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        if(state!=null){selected=state.getLong("selected",0);extraHeaders=state.getString("headers","");}
        boolean largeLabels=getResources().getConfiguration().fontScale>1.3f;LinearLayout root=column();root.setBackgroundColor(BACKGROUND);root.setPadding(dp(18),0,dp(18),0);root.setFocusableInTouchMode(true);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{applyWorkspaceInsets(view,insets);return insets;});
        LinearLayout toolbar=row();MaterialButton back=button(getString(R.string.common_back));back.setOnClickListener(v->finish());toolbar.addView(back);TextView title=text(getString(R.string.ws_title),24,TEXT);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams titleSpace=new LinearLayout.LayoutParams(0,-2,1);titleSpace.leftMargin=dp(12);toolbar.addView(title,titleSpace);root.addView(toolbar);
        status=text(getString(R.string.ws_preparing),12,MUTED);add(root,status,6);
        sessionPicker=new Spinner(this);sessionPicker.setContentDescription(getString(R.string.ws_all));root.addView(sessionPicker,new LinearLayout.LayoutParams(-1,dp(largeLabels?64:48)));
        sessionPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> a,View v,int p,long id){if(!changingSelection){selected=p==0?0:sessions.get(p-1).id;refreshMessages();}}public void onNothingSelected(android.widget.AdapterView<?> a){}});
        TextInputLayout target=field(getString(R.string.ws_target),1);url=(TextInputEditText)target.getEditText();url.setText(R.string.ws_example_url);url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);add(root,target,8);
        LinearLayout connectionActions=row();if(largeLabels)connectionActions.setOrientation(LinearLayout.VERTICAL);connect=button(getString(R.string.ws_connect));connect.setOnClickListener(v->connect());connectionActions.addView(connect,actionParams(largeLabels));
        disconnect=button(getString(R.string.ws_disconnect));disconnect.setOnClickListener(v->{long id=selected;if(hub!=null)execute(()->hub.closeSession(id));});connectionActions.addView(disconnect,actionParams(largeLabels));MaterialButton headers=button(getString(R.string.ws_headers));headers.setOnClickListener(v->editHeaders());connectionActions.addView(headers,actionParams(largeLabels));add(root,connectionActions,4);
        LinearLayout interception=row();if(largeLabels)interception.setOrientation(LinearLayout.VERTICAL);hold=new MaterialSwitch(this);hold.setText(getString(R.string.ws_hold));hold.setTextColor(TEXT);hold.setContentDescription(getString(R.string.ws_hold_description));hold.setOnCheckedChangeListener((view,enabled)->{if(hub!=null&&hub.interceptor().isEnabled()!=enabled)execute(()->hub.interceptor().setEnabled(enabled));});interception.addView(hold,actionParams(largeLabels));pending=text(getResources().getQuantityString(R.plurals.ws_waiting,0,0),12,PRIMARY);pending.setPadding(dp(12),dp(14),dp(12),dp(14));pending.setMinHeight(dp(48));pending.setFocusable(true);pending.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);pending.setOnClickListener(v->reviewQueue());interception.addView(pending);add(root,interception,8);
        TextView hint=text(getString(R.string.ws_hint),11,MUTED);add(root,hint,2);
        RecyclerView history=new RecyclerView(this);history.setLayoutManager(new LinearLayoutManager(this));adapter=new MessageAdapter();history.setAdapter(adapter);LinearLayout.LayoutParams list=new LinearLayout.LayoutParams(-1,Math.max(dp(160),Math.min(dp(280),getResources().getDisplayMetrics().heightPixels/4)));list.topMargin=dp(10);root.addView(history,list);
        LinearLayout composeHeading=row();if(largeLabels)composeHeading.setOrientation(LinearLayout.VERTICAL);mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{getString(R.string.ws_text_utf8),getString(R.string.ws_binary_hex)}));mode.setContentDescription(getString(R.string.ws_compose));composeHeading.addView(mode,actionParams(largeLabels));MaterialButton clear=button(getString(R.string.ws_clear));clear.setOnClickListener(v->{if(hub!=null)execute(()->hub.clearHistory());});composeHeading.addView(clear,actionParams(largeLabels));root.addView(composeHeading);
        TextInputLayout message=field(getString(R.string.ws_compose),3);composer=(TextInputEditText)message.getEditText();composer.setTypeface(Typeface.MONOSPACE);composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(UI_EDIT_BYTES*2)});root.addView(message);
        send=button(getString(R.string.ws_send));send.setOnClickListener(v->send());add(root,send,6);ScrollView workspace=new ScrollView(this);workspace.setFillViewport(true);workspace.setBackgroundColor(BACKGROUND);workspace.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(workspace);root.requestFocus();setReady(false);
        execute(()->{try{WebSocketHub prepared=SprigApp.get().getProxy().getWebSockets();onMain(()->{hub=prepared;loading=false;if(resumed)hub.addListener(listener);setReady(true);refresh();});}catch(Exception failure){onMain(()->{loading=false;status.setText(message(failure));});}});
    }
    @Override protected void onStart(){super.onStart();resumed=true;if(hub!=null){hub.addListener(listener);refresh();}}
    @Override protected void onStop(){resumed=false;if(hub!=null)hub.removeListener(listener);super.onStop();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);work.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(@NonNull Bundle out){out.putLong("selected",selected);out.putString("headers",extraHeaders);super.onSaveInstanceState(out);}
    private void refresh(){if(hub==null)return;sessions=hub.sessions();List<String> labels=new ArrayList<>();labels.add(getString(R.string.ws_all));int index=0;for(WebSocketHub.Session session:sessions){labels.add(getString(R.string.ws_session_label,session.id,session.state,session.url));if(session.id==selected)index=labels.size()-1;}
        if(!labels.equals(sessionLabels)){changingSelection=true;sessionLabels=new ArrayList<>(labels);sessionPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));sessionPicker.setSelection(index);changingSelection=false;}if(index==0)selected=0;
        hold.setChecked(hub.interceptor().isEnabled());int waiting=hub.interceptor().pending().size();pending.setText(getResources().getQuantityString(R.plurals.ws_waiting,waiting,waiting));status.setText(sessions.isEmpty()?getString(R.string.ws_idle):getString(R.string.ws_sessions_summary,sessions.size(),hub.messages(0).size()));refreshMessages();}
    private void refreshMessages(){if(hub==null)return;List<WebSocketHub.Message> messages=hub.messages(selected);adapter.submit(messages.size()>200?messages.subList(0,200):messages);boolean open=false;for(WebSocketHub.Session session:sessions)if(session.id==selected){open=session.state.equals("OPEN");if(!session.error.isEmpty())status.setText(session.error);}send.setEnabled(open);disconnect.setEnabled(open);}
    private void setReady(boolean ready){connect.setEnabled(ready);hold.setEnabled(ready);send.setEnabled(false);disconnect.setEnabled(false);}
    private void connect(){if(hub==null||loading)return;String target=url.getText().toString().trim(),headersSnapshot=extraHeaders;connect.setEnabled(false);status.setText(getString(R.string.ws_connecting));execute(()->{try{WebSocketHub.Session session=hub.connect(target,headersSnapshot);onMain(()->{selected=session.id;connect.setEnabled(true);refresh();});}catch(Exception error){onMain(()->{connect.setEnabled(true);showError(error);});}});}
    private void send(){if(hub==null||selected==0)return;long id=selected;String value=composer.getText().toString();int format=mode.getSelectedItemPosition();execute(()->{try{if(format==0)hub.sendText(id,value);else hub.sendBinary(id,unhex(value));onMain(()->status.setText(getString(R.string.ws_queued)));}catch(Exception error){onMain(()->showError(error));}});}
    private void editHeaders(){EditText editor=editor(extraHeaders,5,16384);new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ws_connection_headers)).setMessage(getString(R.string.ws_headers_hint)).setView(editor).setPositiveButton(getString(R.string.common_save),(d,w)->extraHeaders=editor.getText().toString()).setNegativeButton(getString(R.string.common_cancel),null).show();}
    private void reviewQueue(){if(hub==null)return;List<WebSocketInterceptor.Pending> list=hub.interceptor().pending();if(list.isEmpty()){new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ws_no_held)).setMessage(getString(R.string.ws_no_held_hint)).setPositiveButton(getString(R.string.common_close),null).show();return;}String[] names=new String[list.size()];for(int i=0;i<names.length;i++){WebSocketInterceptor.Pending p=list.get(i);names[i]=getString(R.string.ws_pending_label,p.sessionId,direction(p.direction),p.opcode==1?getString(R.string.ws_text):getString(R.string.ws_binary),p.payload.length);}new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ws_held)).setItems(names,(dialog,which)->review(list.get(which))).setNegativeButton(getString(R.string.common_close),null).show();}
    private void review(WebSocketInterceptor.Pending pendingMessage){boolean editable=pendingMessage.payload.length<=UI_EDIT_BYTES;String value=editable?(pendingMessage.opcode==1?new String(pendingMessage.payload,StandardCharsets.UTF_8):hex(pendingMessage.payload)):preview(pendingMessage.payload,pendingMessage.opcode);EditText edit=editor(value,8,UI_EDIT_BYTES*2);edit.setEnabled(editable);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ws_review_title,pendingMessage.sessionId,direction(pendingMessage.direction))).setMessage(editable?getString(R.string.ws_edit_hint):getString(R.string.ws_large_hint)).setView(edit).setPositiveButton(getString(R.string.common_forward),null).setNegativeButton(getString(R.string.common_drop),(d,w)->execute(()->hub.interceptor().drop(pendingMessage.id))).setNeutralButton(getString(R.string.common_wait),null).create();dialog.setOnShowListener(v->dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String edited=edit.getText().toString();execute(()->{try{byte[] bytes=editable?(pendingMessage.opcode==1?edited.getBytes(StandardCharsets.UTF_8):unhex(edited)):pendingMessage.payload;hub.interceptor().forward(pendingMessage.id,bytes);onMain(dialog::dismiss);}catch(Exception error){onMain(()->edit.setError(message(error)));}});}));dialog.show();}
    private void detail(WebSocketHub.Message message){LinearLayout body=column();body.setPadding(dp(22),0,dp(22),0);TextView meta=text(getString(R.string.ws_message_meta,direction(message.direction),message.disposition,message.originalLength,message.truncated?getString(R.string.ws_capture_truncated):""),12,MUTED);body.addView(meta);TextView bytes=text(message.opcode==1?new String(message.payload,StandardCharsets.UTF_8):hex(message.payload),12,TEXT);bytes.setTypeface(Typeface.MONOSPACE);bytes.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(bytes);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(300)));
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ws_message_title,message.id)).setView(body).setPositiveButton(getString(R.string.ws_replay),(d,w)->{long target=selected==0?message.sessionId:selected;execute(()->{try{hub.replay(target,message.id);}catch(Exception error){onMain(()->showError(error));}});}).setNeutralButton(getString(R.string.ws_copy),(d,w)->((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.ws_clipboard),message.opcode==1?new String(message.payload,StandardCharsets.UTF_8):hex(message.payload)))).setNegativeButton(getString(R.string.common_close),null).create();dialog.setOnShowListener(v->dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(!message.truncated&&(message.opcode==1||message.opcode==2)));dialog.show();}
    private final class MessageAdapter extends ListAdapter<WebSocketHub.Message,MessageHolder>{MessageAdapter(){super(new DiffUtil.ItemCallback<WebSocketHub.Message>(){public boolean areItemsTheSame(@NonNull WebSocketHub.Message a,@NonNull WebSocketHub.Message b){return a.id==b.id;}public boolean areContentsTheSame(@NonNull WebSocketHub.Message a,@NonNull WebSocketHub.Message b){return a.id==b.id&&a.sessionId==b.sessionId&&a.opcode==b.opcode&&a.timeMillis==b.timeMillis&&a.originalLength==b.originalLength&&a.truncated==b.truncated&&a.direction.equals(b.direction)&&a.disposition.equals(b.disposition)&&Arrays.equals(a.payload,b.payload);}});}void submit(List<WebSocketHub.Message> next){submitList(new ArrayList<>(next));}@NonNull public MessageHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){LinearLayout card=column();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackgroundColor(SURFACE);RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(-1,-2);p.bottomMargin=dp(6);card.setLayoutParams(p);TextView heading=text("",12,PRIMARY),content=text("",12,TEXT);content.setTypeface(Typeface.MONOSPACE);content.setMaxLines(3);card.addView(heading);card.addView(content);return new MessageHolder(card,heading,content);}public void onBindViewHolder(@NonNull MessageHolder holder,int position){WebSocketHub.Message item=getItem(position);holder.heading.setText(getString(R.string.ws_message_heading,item.sessionId,direction(item.direction),opcode(item.opcode),item.disposition));holder.content.setText(preview(item.payload,item.opcode));holder.itemView.setOnClickListener(v->detail(item));}}
    private static final class MessageHolder extends RecyclerView.ViewHolder{final TextView heading,content;MessageHolder(View v,TextView h,TextView c){super(v);heading=h;content=c;}}
    private void showError(Throwable error){status.setText(message(error));new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ws_error)).setMessage(message(error)).setPositiveButton(getString(R.string.common_close),null).show();}
    private void onMain(Runnable action){main.post(()->{if(!isDestroyed()&&!isFinishing())action.run();});}
    private boolean execute(Runnable action){try{work.execute(action);return true;}catch(RejectedExecutionException busy){if(hub!=null&&hold!=null)hold.setChecked(hub.interceptor().isEnabled());status.setText(getString(R.string.ws_busy));if(connect!=null)connect.setEnabled(hub!=null);return false;}}
    void applyWorkspaceInsets(View view,WindowInsetsCompat insets){Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.ime());view.setPadding(dp(18)+bars.left,bars.top,dp(18)+bars.right,bars.bottom);}
    private LinearLayout.LayoutParams actionParams(boolean vertical){return new LinearLayout.LayoutParams(vertical?-1:0,-2,vertical?0:1);}
    private TextInputLayout field(String hint,int lines){TextInputLayout layout=new TextInputLayout(this);layout.setHint(hint);TextInputEditText input=new TextInputEditText(this);input.setMinLines(lines);input.setMaxLines(lines);input.setTextSize(13);input.setTextColor(TEXT);input.setSaveEnabled(false);input.setSaveFromParentEnabled(false);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});layout.addView(input,new LinearLayout.LayoutParams(-1,-2));return layout;}
    private EditText editor(String initial,int lines,int limit){EditText edit=new EditText(this);edit.setTypeface(Typeface.MONOSPACE);edit.setTextColor(TEXT);edit.setTextSize(12);edit.setMinLines(lines);edit.setMaxLines(lines);edit.setSaveEnabled(false);edit.setSaveFromParentEnabled(false);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});edit.setText(initial);return edit;}
    private MaterialButton button(String label){MaterialButton button=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);button.setText(label);button.setTextSize(12);button.setTextColor(PRIMARY);button.setMinHeight(dp(48));button.setMaxLines(3);return button;}
    private TextView text(String value,int size,int color){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);return text;}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout row(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.HORIZONTAL);layout.setGravity(Gravity.CENTER_VERTICAL);return layout;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}
    private String direction(String value){return value.equals(WebSocketHub.CLIENT_TO_SERVER)?getString(R.string.ws_c2s):getString(R.string.ws_s2c);}
    private String opcode(int value){return value==1?getString(R.string.ws_text):value==2?getString(R.string.ws_binary):value==8?getString(R.string.common_close):value==9?"Ping":value==10?"Pong":getString(R.string.ws_frame);}
    private static String preview(byte[] bytes,int opcode){byte[] prefix=Arrays.copyOf(bytes,Math.min(bytes.length,opcode==1?600:160));return (opcode==1?new String(prefix,StandardCharsets.UTF_8):hex(prefix))+(bytes.length>prefix.length?"…":"");}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder(bytes.length*2);char[] digits="0123456789abcdef".toCharArray();for(byte b:bytes)result.append(digits[(b>>>4)&15]).append(digits[b&15]);return result.toString();}
    private byte[] unhex(String value){value=value.replaceAll("\\s","");if((value.length()&1)!=0||!value.matches("[0-9a-fA-F]*"))throw new IllegalArgumentException(getString(R.string.ws_hex_error));byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    private static String message(Throwable failure){return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();}
}
