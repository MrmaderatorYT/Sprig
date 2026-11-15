package com.ccs.sprig.oast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON boundary: bounded input, explicit map values, no reflective application models. */
public final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() { }
    public static String encode(Object value) { return GSON.toJson(value); }
    public static Map<String,Object> object(String input) throws IOException {
        if(input.length()>512*1024) throw new IOException("JSON exceeds 512 KiB");
        try {
            Object value=convert(JsonParser.parseString(input),0);
            if(!(value instanceof Map)) throw new IOException("Expected JSON object");
            @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>) value;
            return result;
        } catch(RuntimeException | StackOverflowError error) { throw new IOException("Invalid JSON response",error); }
    }
    private static Object convert(JsonElement e,int depth) throws IOException {
        if(depth>32) throw new IOException("JSON nesting exceeds 32 levels");
        if(e.isJsonNull()) return null;
        if(e.isJsonObject()) {
            Map<String,Object> map=new LinkedHashMap<>();
            for(Map.Entry<String,JsonElement> entry:e.getAsJsonObject().entrySet()) map.put(entry.getKey(),convert(entry.getValue(),depth+1));
            return map;
        }
        if(e.isJsonArray()) {List<Object> list=new ArrayList<>();for(JsonElement item:e.getAsJsonArray()) list.add(convert(item,depth+1));return list;}
        if(e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if(e.getAsJsonPrimitive().isNumber()) return e.getAsBigDecimal();
        return e.getAsString();
    }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]);return map;
    }
    public static String string(Map<String,Object> map,String key) {Object value=map.get(key);return value==null?"":String.valueOf(value);}
    public static long number(Map<String,Object> map,String key) {Object value=map.get(key);return value instanceof Number?((Number)value).longValue():0;}
}
