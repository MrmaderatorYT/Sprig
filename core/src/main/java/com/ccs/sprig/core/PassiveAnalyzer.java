package com.ccs.sprig.core;

import com.ccs.sprig.core.model.Exchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Context-limited observations from already captured traffic; sends no network requests. */
public final class PassiveAnalyzer {
    public static final class Finding {
        public final String severity, title, detail;
        Finding(String severity, String title, String detail) { this.severity=severity;this.title=title;this.detail=detail; }
    }
    private PassiveAnalyzer() { }
    public static List<Finding> analyze(Exchange e) {
        List<Finding> findings = new ArrayList<>();
        if (e.status == 0) return findings;
        boolean https = e.url != null && e.url.startsWith("https://");
        String headers = e.responseHeaders == null ? "" : e.responseHeaders;
        if (!https) findings.add(new Finding("Medium", "Cleartext HTTP", "This exchange travels without TLS. Review whether the endpoint handles sensitive data."));
        if (https && !has(headers,"strict-transport-security"))
            findings.add(new Finding("Info","HSTS not observed","This response has no Strict-Transport-Security header. Applicability depends on the host and client."));
        String type = header(headers,"content-type").toLowerCase(Locale.ROOT);
        if (type.contains("text/html")) {
            if (!has(headers,"content-security-policy")) findings.add(new Finding("Info","CSP not observed","No Content-Security-Policy was observed on this HTML response. This is a hardening observation, not proof of an exploit."));
            if (!has(headers,"x-content-type-options")) findings.add(new Finding("Low","MIME sniffing protection not observed","Consider X-Content-Type-Options: nosniff for browser-served HTML and assets."));
        }
        for (String line : headers.split("\r?\n")) {
            if(findings.size()>=100) break;
            if (!line.regionMatches(true,0,"Set-Cookie:",0,11)) continue;
            String cookie=line.substring(11).trim(); String[] parts=cookie.split(";",-1);
            String name=parts[0].split("=",2)[0]; if(name.length()>80) name=name.substring(0,80)+"…";
            boolean secure=false,httpOnly=false,sameSite=false;
            for(int i=1;i<parts.length;i++) {
                String attr=parts[i].trim().toLowerCase(Locale.ROOT);
                secure|=attr.equals("secure");httpOnly|=attr.equals("httponly");sameSite|=attr.startsWith("samesite=");
            }
            if (https && !secure) findings.add(new Finding("Low","Cookie without Secure: "+name,"Review whether this cookie should be restricted to HTTPS. Cookie values are omitted from this finding."));
            if (!httpOnly) findings.add(new Finding("Info","Cookie without HttpOnly: "+name,"JavaScript can read this cookie. Some application cookies intentionally require script access."));
            if (!sameSite) findings.add(new Finding("Info","Cookie without explicit SameSite: "+name,"Review cross-site cookie behavior for the supported clients; browser defaults differ."));
        }
        if ("*".equals(header(headers,"access-control-allow-origin")) && "true".equalsIgnoreCase(header(headers,"access-control-allow-credentials")))
            findings.add(new Finding("Info","Conflicting CORS headers","Wildcard origin with credentials is rejected by browsers; inspect the intended CORS configuration."));
        return findings;
    }
    private static boolean has(String raw,String key) {return !header(raw,key).isEmpty();}
    private static String header(String raw,String key) {
        for(String line:raw.split("\r?\n")) {int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).equalsIgnoreCase(key)) return line.substring(colon+1).trim();}
        return "";
    }
}
