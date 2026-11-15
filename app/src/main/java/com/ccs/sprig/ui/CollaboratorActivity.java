package com.ccs.sprig.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.ccs.sprig.SprigApp;
import com.ccs.sprig.R;
import com.ccs.sprig.oast.CollaboratorClient;
import com.ccs.sprig.oast.Interaction;
import com.ccs.sprig.oast.Json;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground OAST workspace; payload sessions and request completions outlive Activity recreation. */
public final class CollaboratorActivity extends AppCompatActivity {
    private static final int BG=0xff101411,SURFACE=0xff1b211d,PRIMARY=0xffb6f3c6,TEXT=0xffe1e8df,MUTED=0xffa9b7ad,ERROR=0xffffb4ab,EXPORT=81;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Model model;
    private TextInputEditText apiEditor,keyEditor;
    private TextView host,status,capabilities;
    private MaterialButton create,poll,revoke;
    private LinearLayout events;
    private boolean resumed;
    private final Runnable ticker=new Runnable(){@Override public void run(){if(resumed){ViewState state=model.snapshot();if(model.auto&&!state.busy&&state.session!=null)model.poll();handler.postDelayed(this,5000);}}};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        model=new ViewModelProvider(this).get(Model.class);
        LinearLayout root=column();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),0,dp(20),0);
        MaterialButton back=button("‹",false);back.setContentDescription("Back");back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(52),dp(56)));
        TextView title=text("Collaborator",24,TEXT);header.addView(title);root.addView(header);
        ScrollView scroll=new ScrollView(this);LinearLayout body=column();body.setPadding(dp(20),dp(10),dp(20),dp(30));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        add(body,text("Generate a private callback address, use it in a request, then collect DNS, HTTP or SMTP interactions from your own server.",13,MUTED),0);
        MaterialCardView configuration=card();LinearLayout config=inside();
        TextInputLayout api=field("Server API URL",false);apiEditor=(TextInputEditText)api.getEditText();apiEditor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        ViewState state=model.snapshot();apiEditor.setText(model.apiDraft!=null?model.apiDraft:state.api.isEmpty()?SprigApp.get().getPreferences().getString("collaborator_url",""):state.api);config.addView(api);
        TextInputLayout key=field("Admin key · needed to generate",true);keyEditor=(TextInputEditText)key.getEditText();add(config,key,12);
        add(config,text("Remote APIs require HTTPS. Keys and payload sessions stay in memory. The server is supplied with the project and needs a public domain for external callbacks.",11,MUTED),12);
        create=button("Generate callback address",true);create.setOnClickListener(v->create());add(config,create,16);configuration.addView(config);add(body,configuration,20);
        MaterialCardView session=card();LinearLayout current=inside();current.addView(text("CALLBACK",10,PRIMARY));
        host=text("No callback address generated",15,TEXT);host.setTypeface(Typeface.MONOSPACE);host.setTextIsSelectable(true);add(current,host,12);
        capabilities=text("",11,MUTED);add(current,capabilities,8);
        MaterialButton copy=button("Copy hostname",false);copy.setOnClickListener(v->{CollaboratorClient.Session active=model.snapshot().session;if(active!=null){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Callback hostname",active.payloadHost));toast("Callback hostname copied");}});add(current,copy,10);
        LinearLayout actions=row();poll=button("Poll now",true);revoke=button("Revoke",false);poll.setOnClickListener(v->model.poll());revoke.setOnClickListener(v->model.revoke());actions.addView(poll,new LinearLayout.LayoutParams(0,dp(52),1));actions.addView(revoke,new LinearLayout.LayoutParams(0,dp(52),1));add(current,actions,8);
        MaterialSwitch autoPoll=new MaterialSwitch(this);autoPoll.setText(getString(R.string.collaborator_autopoll));autoPoll.setTextColor(TEXT);autoPoll.setChecked(model.auto);autoPoll.setOnCheckedChangeListener((b,value)->model.auto=value);add(current,autoPoll,10);
        session.addView(current);add(body,session,16);
        LinearLayout resultHeader=row();resultHeader.setGravity(Gravity.CENTER_VERTICAL);resultHeader.addView(text("Interactions",20,TEXT),new LinearLayout.LayoutParams(0,-2,1));MaterialButton export=button(getString(R.string.common_export),false);export.setOnClickListener(v->export());resultHeader.addView(export);add(body,resultHeader,22);
        status=text("",12,MUTED);add(body,status,8);events=column();add(body,events,12);
        setContentView(root);root.requestFocus();render();
    }
    @Override protected void onStart(){super.onStart();resumed=true;model.observer=()->handler.post(()->{if(resumed&&!isDestroyed())render();});handler.post(ticker);render();}
    @Override protected void onStop(){resumed=false;model.apiDraft=apiEditor.getText().toString();model.observer=null;handler.removeCallbacks(ticker);super.onStop();}
    @Override protected void onDestroy(){model.observer=null;handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private void create(){
        ViewState state=model.snapshot();if(state.busy)return;String api=apiEditor.getText().toString().trim(),key=keyEditor.getText().toString();
        if(key.length()<32){toast("Enter the private server admin key");return;}
        Runnable generate=()->{keyEditor.setText("");model.generate(api,key);};
        if(state.session!=null)new MaterialAlertDialogBuilder(this).setTitle("Replace callback address?").setMessage("The previous address remains valid on the server until revoked or expired. Export its events first.").setPositiveButton("Generate new",(d,w)->generate.run()).setNegativeButton(getString(R.string.common_cancel),null).show();else generate.run();
    }
    private void render(){
        ViewState state=model.snapshot();CollaboratorClient.Session session=state.session;
        host.setText(session==null?"No callback address generated":session.payloadHost);capabilities.setText(session==null?"":String.join(" · ",session.protocols)+"\nExpires "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(session.expiresAt)));
        poll.setEnabled(!state.busy&&session!=null);revoke.setEnabled(!state.busy&&session!=null);create.setEnabled(!state.busy);
        status.setTextColor(state.error?ERROR:MUTED);status.setText(state.busy?"Contacting private Collaborator server…":state.message.isEmpty()?state.events.size()+" recorded interaction(s)":state.message);
        events.removeAllViews();
        if(state.events.isEmpty()){add(events,text("Your callback interactions will appear here. Generating an address does not contact the system you are testing.",13,MUTED),8);return;}
        int first=Math.max(0,state.events.size()-100);
        for(int i=state.events.size()-1;i>=first;i--){Interaction event=state.events.get(i);MaterialCardView card=card();LinearLayout box=inside();box.addView(text(event.protocol+"  ·  "+new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date(event.timeMillis)),12,PRIMARY));add(box,text(event.summary,13,TEXT),7);add(box,text(event.remoteAddress,11,MUTED),6);card.addView(box);card.setOnClickListener(v->{TextView details=text(event.details,11,TEXT);details.setTypeface(Typeface.MONOSPACE);details.setTextIsSelectable(true);details.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(details);new MaterialAlertDialogBuilder(this).setTitle(event.protocol+" interaction").setView(scroll).setPositiveButton(getString(R.string.common_close),null).show();});add(events,card,8);}
    }
    private void export(){if(model.snapshot().events.isEmpty()){toast("No interactions to export");return;}startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"sprig-interactions.json"),EXPORT);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri target=data.getData();List<Object> output=new ArrayList<>();for(Interaction event:model.snapshot().events)output.add(event.json());String json=Json.encode(Json.map("generator","Sprig","events",output));
        android.content.ContentResolver resolver=getApplicationContext().getContentResolver();
        model.export(()->{try(OutputStream stream=resolver.openOutputStream(target,"wt")){if(stream==null)throw new IOException("Cannot open export");stream.write(json.getBytes(StandardCharsets.UTF_8));}});
    }

    interface ExportAction{void write()throws IOException;}
    interface Api {
        CollaboratorClient.Session create(String key)throws IOException;
        CollaboratorClient.Poll poll(CollaboratorClient.Session session,long cursor)throws IOException;
        void revoke(CollaboratorClient.Session session)throws IOException;
    }
    interface ClientFactory{Api client(String base)throws IOException;}
    /** Data only: no Activity, View, Handler or Context is retained by this process-local owner. */
    private static final class Shared {
        final ExecutorService worker=UiWorkQueue.create("sprig-collaborator",2);
        final CopyOnWriteArrayList<Model> models=new CopyOnWriteArrayList<>();
        String api="",message="";CollaboratorClient.Session session;long cursor,bytes;boolean busy,error,evicted;
        final List<Interaction> events=new ArrayList<>();
        void notifyModels(){for(Model model:models){Runnable observer=model.observer;if(observer!=null)observer.run();}}
    }
    private static final Shared SHARED=new Shared();
    static final class ViewState {
        final String api,message;final CollaboratorClient.Session session;final List<Interaction> events;final boolean busy,error;
        ViewState(Shared shared){api=shared.api;message=shared.message;session=shared.session;events=new ArrayList<>(shared.events);busy=shared.busy;error=shared.error;}
    }
    /** A retained presentation owner observes process-local completion even when the screen rotates. */
    public static final class Model extends ViewModel {
        volatile Runnable observer;boolean auto;String apiDraft;
        ClientFactory clients=base->{CollaboratorClient client=new CollaboratorClient(base);return new Api(){
            @Override public CollaboratorClient.Session create(String key)throws IOException{return client.create(key);}
            @Override public CollaboratorClient.Poll poll(CollaboratorClient.Session session,long cursor)throws IOException{return client.poll(session,cursor);}
            @Override public void revoke(CollaboratorClient.Session session)throws IOException{client.revoke(session);}
        };};
        public Model(){SHARED.models.add(this);}
        ViewState snapshot(){synchronized(SHARED){return new ViewState(SHARED);}}
        private boolean begin(){synchronized(SHARED){if(SHARED.busy)return false;SHARED.busy=true;SHARED.error=false;}SHARED.notifyModels();return true;}
        void generate(String api,String key){
            if(!begin())return;ClientFactory factory=clients;
            submitOperation(()->{try{CollaboratorClient.Session created=factory.client(api).create(key);
                synchronized(SHARED){SHARED.api=api;SHARED.session=created;SHARED.cursor=0;SHARED.events.clear();SHARED.bytes=0;SHARED.evicted=false;SHARED.message="Callback address ready. Paste it into a test request, then poll for interactions.";}
                SprigApp.get().getPreferences().edit().putString("collaborator_url",api).apply();complete(null);
            }catch(Exception error){complete(error);}});
        }
        void poll(){
            CollaboratorClient.Session session;long cursor;String api;
            synchronized(SHARED){if(SHARED.busy||SHARED.session==null)return;session=SHARED.session;cursor=SHARED.cursor;api=SHARED.api;}
            if(!begin())return;ClientFactory factory=clients;
            submitOperation(()->{try{CollaboratorClient.Poll result=factory.client(api).poll(session,cursor);
                synchronized(SHARED){if(SHARED.session==session){for(Interaction event:result.events)append(event);SHARED.cursor=result.cursor;SHARED.message=result.truncated?"Some older interactions expired on the server. Export received events to retain them.":result.events.isEmpty()?"No new interactions. The server must be reachable from the tested system.":result.events.size()+" new interaction(s)";if(SHARED.evicted)SHARED.message+=" Older local events were evicted from the memory limit.";}}
                complete(null);
            }catch(Exception error){complete(error);}});
        }
        void revoke(){
            CollaboratorClient.Session session;String api;synchronized(SHARED){if(SHARED.busy||SHARED.session==null)return;session=SHARED.session;api=SHARED.api;}
            if(!begin())return;ClientFactory factory=clients;
            submitOperation(()->{try{factory.client(api).revoke(session);synchronized(SHARED){if(SHARED.session==session)SHARED.session=null;SHARED.message="Callback address revoked. Exported events remain available.";}complete(null);}catch(Exception error){complete(error);}});
        }
        void export(ExportAction action){UiWorkQueue.submit(SHARED.worker,()->{try{action.write();synchronized(SHARED){SHARED.message="Interactions exported";SHARED.error=false;}}catch(Exception error){synchronized(SHARED){SHARED.message="Export failed: "+limit(error.getMessage(),400);SHARED.error=true;}}SHARED.notifyModels();},()->{synchronized(SHARED){SHARED.error=true;SHARED.message=SprigApp.get().getString(R.string.workspace_busy);}SHARED.notifyModels();});}
        private void submitOperation(Runnable task){UiWorkQueue.submit(SHARED.worker,task,()->complete(new IOException(SprigApp.get().getString(R.string.workspace_busy))));}
        private void complete(Exception error){
            synchronized(SHARED){SHARED.busy=false;SHARED.error=error!=null;if(error!=null){String message=error.getMessage();SHARED.message=limit(message==null?error.getClass().getSimpleName():message,500);}}
            SHARED.notifyModels();
        }
        private static void append(Interaction event){
            Interaction bounded=new Interaction(event.sequence,event.timeMillis,limit(event.protocol,32),limit(event.remoteAddress,128),limit(event.summary,1024),limit(event.details,8192));
            SHARED.events.add(bounded);SHARED.bytes+=cost(bounded);long budget=Math.max(1024*1024,Math.min(8L*1024*1024,Runtime.getRuntime().maxMemory()/16));
            while(SHARED.events.size()>1000||SHARED.bytes>budget){SHARED.bytes-=cost(SHARED.events.remove(0));SHARED.evicted=true;}
        }
        private static long cost(Interaction event){return 128L+2L*(event.protocol.length()+event.remoteAddress.length()+event.summary.length()+event.details.length());}
        private static String limit(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max)+"…";}
        @Override protected void onCleared(){observer=null;SHARED.models.remove(this);}
    }
    private TextInputLayout field(String hint,boolean secret){TextInputLayout layout=new TextInputLayout(this,null,com.google.android.material.R.attr.textInputOutlinedStyle);layout.setHint(hint);layout.setSaveEnabled(false);TextInputEditText edit=new TextInputEditText(layout.getContext());edit.setSingleLine(true);edit.setTextSize(14);edit.setSaveEnabled(false);edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});if(secret)edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);layout.addView(edit,new LinearLayout.LayoutParams(-1,-2));return layout;}
    private MaterialCardView card(){MaterialCardView card=new MaterialCardView(this);card.setCardBackgroundColor(SURFACE);card.setRadius(dp(22));card.setStrokeWidth(0);card.setCardElevation(0);return card;}
    private LinearLayout inside(){LinearLayout box=column();box.setPadding(dp(18),dp(18),dp(18),dp(18));return box;}
    private MaterialButton button(String title,boolean filled){MaterialButton b=new MaterialButton(this,null,filled?com.google.android.material.R.attr.materialButtonStyle:com.google.android.material.R.attr.materialButtonOutlinedStyle);b.setText(title);b.setAllCaps(false);b.setCornerRadius(dp(18));return b;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(2),1);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
}
