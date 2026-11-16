import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** JDK17 audit utility, not shipped in Android. Reads DEX method references, not decompiler guesses.
 * Absence of a reference is not proof of absence of reflection, native or user-payload transmissions.
 */
public final class ReleasePrivacyAudit {
    private static final int MAX_DEX = 64 * 1024 * 1024;
    private static final Set<String> ID_METHODS = Set.of("getDeviceId", "getImei", "getMeid", "getSubscriberId",
            "getSimSerialNumber", "getSerial", "getHardwareAddress", "getMacAddress", "getAdvertisingIdInfo", "getId");
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3||(args.length==3&&!args[2].equals("--check")))
            throw new IllegalArgumentException("Usage: java tools/ReleasePrivacyAudit.java APK REPORT.json [--check]");
        Map<String,SortedSet<String>> found=new LinkedHashMap<>();
        for(String key:List.of("packageInventoryApis","packageMetadataApis","inputEventDeviceIdApis","persistentIdCandidateApis",
                "secureSettingsApis","identifierStringMarkers","trackingSdkClasses")) found.put(key,new TreeSet<>());
        int dexCount=0,nativeCount=0;
        try(ZipFile zip=new ZipFile(args[0])) {
            for(ZipEntry entry:Collections.list(zip.entries())) {
                if(entry.getName().endsWith(".so")) nativeCount++;
                if(!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                dexCount++;
                byte[] bytes;
                try(InputStream in=zip.getInputStream(entry)){bytes=in.readNBytes(MAX_DEX+1);}
                if(bytes.length>MAX_DEX) throw new IOException("DEX exceeds 64MiB audit limit");
                readDex(bytes,found);
            }
        }
        if(dexCount==0)throw new IOException("No standard DEX entries in APK");
        byte[] digest;
        try(InputStream in=Files.newInputStream(Path.of(args[0]))) {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)sha.update(buffer,0,n);digest=sha.digest();
        }
        StringBuilder json=new StringBuilder("{\n  \"sha256\": ").append(quote(HexFormat.of().formatHex(digest)))
                .append(",\n  \"dexFiles\": ").append(dexCount).append(",\n  \"nativeLibraries\": ").append(nativeCount);
        for(var item:found.entrySet()) {
            json.append(",\n  ").append(quote(item.getKey())).append(": [");
            boolean first=true;for(String value:item.getValue()) {if(!first)json.append(',');json.append('\n').append("    ").append(quote(value));first=false;}
            if(!first)json.append('\n').append("  ");json.append(']');
        }
        json.append("\n}\n");Files.writeString(Path.of(args[1]),json);System.out.print(json);
        if(args.length==3) {
            boolean broad=found.get("packageInventoryApis").stream().anyMatch(s->s.endsWith("->getInstalledApplications")||s.endsWith("->getInstalledPackages"));
            if(broad||!found.get("persistentIdCandidateApis").isEmpty()||!found.get("secureSettingsApis").isEmpty()
                    ||!found.get("identifierStringMarkers").isEmpty()||!found.get("trackingSdkClasses").isEmpty())
                throw new IllegalStateException("Privacy boundary changed: review the report; this does not by itself prove data transmission.");
        }
    }
    private static void readDex(byte[] data,Map<String,SortedSet<String>> found)throws IOException {
        if(data.length<112||data[0]!='d'||data[1]!='e'||data[2]!='x'||data[3]!='\n')throw new IOException("Not standard DEX");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int stringsCount=count(b,56,1_000_000),stringsOffset=b.getInt(60);
        check(data,stringsOffset,4L*stringsCount);String[] strings=new String[stringsCount];
        for(int i=0;i<stringsCount;i++) {
            int pos=b.getInt(stringsOffset+i*4);check(data,pos,1);
            int prefix=0;while((data[pos++]&128)!=0){if(++prefix>4||pos>=data.length)throw new IOException("Invalid DEX string");}
            int end=pos;while(end<data.length&&data[end]!=0)end++;if(end==data.length)throw new IOException("Unterminated DEX string");
            String s=new String(data,pos,end-pos,StandardCharsets.UTF_8);strings[i]=s;
            if(Set.of("android_id","advertising_id","advertisingId","firebase_installation_id").contains(s))found.get("identifierStringMarkers").add(s);
            if(s.startsWith("Lcom/google/firebase/")||s.startsWith("Lcom/appsflyer/")||s.startsWith("Lcom/adjust/sdk/")
                    ||s.startsWith("Lcom/google/android/gms/ads/identifier/")||s.startsWith("Lcom/facebook/appevents/"))found.get("trackingSdkClasses").add(s);
        }
        int typeCount=count(b,64,65536),typeOffset=b.getInt(68);check(data,typeOffset,4L*typeCount);String[] types=new String[typeCount];
        for(int i=0;i<typeCount;i++)types[i]=strings[b.getInt(typeOffset+4*i)];
        int methodCount=count(b,88,65536),methodOffset=b.getInt(92);check(data,methodOffset,8L*methodCount);
        for(int i=0;i<methodCount;i++) {
            int at=methodOffset+8*i;String owner=types[Short.toUnsignedInt(b.getShort(at))],name=strings[b.getInt(at+4)];
            String signature=owner+"->"+name;
            if(owner.equals("Landroid/content/pm/PackageManager;")&&Set.of("getPackageInfo","getApplicationInfo","queryIntentContentProviders","getLaunchIntentForPackage").contains(name))found.get("packageMetadataApis").add(signature);
            if(name.equals("getInstalledApplications")||name.equals("getInstalledPackages")||name.equals("queryIntentActivities"))found.get("packageInventoryApis").add(signature);
            if(name.equals("getDeviceId")&&Set.of("Landroid/view/InputEvent;","Landroid/view/KeyEvent;","Landroid/view/MotionEvent;").contains(owner))found.get("inputEventDeviceIdApis").add(signature);
            else if(ID_METHODS.contains(name)&&(owner.startsWith("Landroid/telephony/")||owner.equals("Landroid/os/Build;")
                    ||owner.equals("Ljava/net/NetworkInterface;")||owner.equals("Landroid/net/wifi/WifiInfo;")
                    ||owner.startsWith("Lcom/google/android/gms/ads/identifier/")||owner.startsWith("Lcom/google/firebase/installations/")))found.get("persistentIdCandidateApis").add(signature);
            if(owner.equals("Landroid/provider/Settings$Secure;"))found.get("secureSettingsApis").add(signature);
        }
    }
    private static int count(ByteBuffer b,int at,int max)throws IOException{int n=b.getInt(at);if(n<0||n>max)throw new IOException("DEX table limit");return n;}
    private static void check(byte[] data,int offset,long length)throws IOException{if(offset<0||length<0||offset+length>data.length)throw new IOException("DEX bounds");}
    private static String quote(String value){return '"'+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+'"';}
}
