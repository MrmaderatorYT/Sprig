package com.ccs.sprig.benchmark;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/** Tiny report writer; metrics have no arbitrary object reflection or remote report assets. */
final class Json {
    private Json(){}
    static void write(Writer writer,Object value)throws IOException {
        if(value==null){writer.write("null");return;}
        if(value instanceof String){string(writer,(String)value);return;}
        if(value instanceof Boolean||value instanceof Number){if(value instanceof Double&&!Double.isFinite((Double)value)||value instanceof Float&&!Float.isFinite((Float)value))throw new IOException("Non-finite metric");writer.write(value.toString());return;}
        if(value instanceof Map){writer.write('{');boolean first=true;for(Object item:((Map<?,?>)value).entrySet()){Map.Entry<?,?> entry=(Map.Entry<?,?>)item;if(!first)writer.write(',');first=false;string(writer,entry.getKey().toString());writer.write(':');write(writer,entry.getValue());}writer.write('}');return;}
        if(value instanceof Iterable){writer.write('[');boolean first=true;for(Object item:(Iterable<?>)value){if(!first)writer.write(',');first=false;write(writer,item);}writer.write(']');return;}
        throw new IOException("Unsupported report value");
    }
    private static void string(Writer writer,String value)throws IOException {writer.write('"');for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case'"':writer.write("\\\"");break;case'\\':writer.write("\\\\");break;case'\n':writer.write("\\n");break;case'\r':writer.write("\\r");break;case'\t':writer.write("\\t");break;default:if(c<32||Character.isSurrogate(c))writer.write(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else writer.write(c);}}writer.write('"');}
}
