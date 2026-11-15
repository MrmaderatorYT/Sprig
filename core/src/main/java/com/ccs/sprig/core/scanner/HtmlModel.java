package com.ccs.sprig.core.scanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded non-executing tokenizer. Ignores comments and script/style text during link discovery. */
final class HtmlModel {
    static final class Tag {
        final String name;final Map<String,String> attrs;final int start,end;
        Tag(String name,Map<String,String> attrs,int start,int end){this.name=name;this.attrs=attrs;this.start=start;this.end=end;}
        String attr(String name){String value=attrs.get(name);return value==null?"":value;}
    }
    static final class Form {final Tag tag;final List<Tag> fields=new ArrayList<>();Form(Tag tag){this.tag=tag;}}
    private static final Pattern ATTRIBUTE=Pattern.compile("([A-Za-z_:][A-Za-z0-9_:.-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    final List<Tag> tags=new ArrayList<>();final List<Form> forms=new ArrayList<>();final List<String> links=new ArrayList<>();final List<int[]> scripts=new ArrayList<>();String baseHref="";
    HtmlModel(String html){
        String lower=html.toLowerCase(Locale.ROOT);int p=0;Form form=null;
        while(p<html.length()&&tags.size()<2000){
            int start=html.indexOf('<',p);if(start<0)break;
            if(lower.startsWith("<!--",start)){int end=html.indexOf("-->",start+4);p=end<0?html.length():end+3;continue;}
            int end=endTag(html,start);if(end<0){p=start+1;continue;}
            String raw=html.substring(start+1,end).trim();boolean closing=raw.startsWith("/");if(closing)raw=raw.substring(1).trim();
            int n=0;while(n<raw.length()&&(Character.isLetterOrDigit(raw.charAt(n))||raw.charAt(n)=='-'||raw.charAt(n)==':'))n++;
            if(n==0){p=end+1;continue;}String name=raw.substring(0,n).toLowerCase(Locale.ROOT);
            if(closing){if(name.equals("form"))form=null;p=end+1;continue;}
            Map<String,String> attrs=new LinkedHashMap<>();Matcher matcher=ATTRIBUTE.matcher(raw.substring(n));int count=0;
            while(matcher.find()&&count++<100){String key=matcher.group(1).toLowerCase(Locale.ROOT),value=matcher.group(2)!=null?matcher.group(2):matcher.group(3)!=null?matcher.group(3):matcher.group(4)!=null?matcher.group(4):"";attrs.putIfAbsent(key,entities(value));}
            Tag tag=new Tag(name,attrs,start,end+1);tags.add(tag);
            if(name.equals("base")&&baseHref.isEmpty()&&!tag.attr("href").isEmpty())baseHref=tag.attr("href");
            if(name.equals("script")||name.equals("style")){int close=lower.indexOf("</"+name,end+1);if(name.equals("script"))scripts.add(new int[]{end+1,close<0?html.length():close});p=close<0?html.length():close;continue;}
            if((name.equals("a")||name.equals("area")||(name.equals("link")&&tag.attr("rel").matches("(?i).*(?:canonical|alternate).*")))&&!tag.attr("href").isEmpty())links.add(tag.attr("href"));
            if((name.equals("iframe")||name.equals("frame"))&&!tag.attr("src").isEmpty())links.add(tag.attr("src"));
            if(name.equals("form")&&forms.size()<50){form=new Form(tag);forms.add(form);}
            else if(form!=null&&(name.equals("input")||name.equals("textarea")||name.equals("select"))&&form.fields.size()<100)form.fields.add(tag);
            p=end+1;
        }
    }
    String context(int position,String marker){
        for(int[] script:scripts)if(position>=script[0]&&position<script[1])return "JavaScript block (not executed)";
        for(Tag tag:tags)if(position>=tag.start&&position<tag.end){for(Map.Entry<String,String> attr:tag.attrs.entrySet())if(attr.getValue().contains(marker))return attr.getKey().startsWith("on")?"event-handler attribute (not executed)":"HTML attribute "+attr.getKey();return "HTML tag";}
        return "HTML text";
    }
    private static int endTag(String text,int start){char quote=0;int limit=Math.min(text.length(),start+8192);for(int i=start+1;i<limit;i++){char c=text.charAt(i);if(quote!=0){if(c==quote)quote=0;}else if(c=='\''||c=='"')quote=c;else if(c=='>')return i;}return -1;}
    static String entities(String value){
        value=value.replace("&amp;","&").replace("&quot;","\"").replace("&apos;","'").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");
        Matcher numbers=Pattern.compile("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});").matcher(value);StringBuffer result=new StringBuffer();
        while(numbers.find())try{String n=numbers.group(1);int code=Integer.parseInt(n.startsWith("x")?n.substring(1):n,n.startsWith("x")?16:10);numbers.appendReplacement(result,Matcher.quoteReplacement(Character.isValidCodePoint(code)?new String(Character.toChars(code)):""));}catch(IllegalArgumentException error){numbers.appendReplacement(result,"");}numbers.appendTail(result);return result.toString();
    }
}
