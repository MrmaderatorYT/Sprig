package com.ccs.sprig.core.scanner;

import com.ccs.sprig.core.PassiveAnalyzer;
import com.ccs.sprig.core.model.Exchange;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Observations from existing responses. No calls are made by passive categories. */
final class PassiveChecks {
    private static final Pattern PRIVATE_KEY=Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----[\\s\\S]{16,20000}?-----END (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----");
    private static final Pattern STACK=Pattern.compile("(?i)(Traceback \\(most recent call last\\)|[a-zA-Z_$][\\w.$]+Exception:|at [\\w.$]+\\([^\\r\\n]{1,160}\\.java:[0-9]+\\)|Microsoft\\.AspNetCore\\.Diagnostics\\.DeveloperExceptionPageMiddleware|Laravel.*Whoops|Django.*DEBUG.*True|PHP (?:Fatal|Parse) error:)");
    private static final Pattern DOM_SOURCE=Pattern.compile("(?:location\\.(?:hash|search|href)|document\\.(?:URL|documentURI|referrer)|window\\.name)");
    private static final Pattern DOM_SINK=Pattern.compile("(?:\\.innerHTML\\s*=|\\.outerHTML\\s*=|document\\.write(?:ln)?\\s*\\(|insertAdjacentHTML\\s*\\(|(?:^|[^\\w.])eval\\s*\\(|new\\s+Function\\s*\\()");
    private PassiveChecks(){ }
    static void analyze(ScanRequest request,Exchange baseline,String body,HtmlModel html,Set<String> enabled,List<Finding> output){
        if(enabled.contains("headers")){
            int count=0;for(PassiveAnalyzer.Finding f:PassiveAnalyzer.analyze(baseline)){if(count++>=30)break;output.add(ScanSupport.finding("passive:"+f.title,"Info","Observation",f.title,f.detail+" Review applicability manually.",request,"","Response status "+baseline.status,baseline));}
            String csp=ScanSupport.header(baseline,"content-security-policy");
            if(ScanSupport.html(baseline)&&!csp.isEmpty()&&(csp.contains("'unsafe-eval'")||csp.contains("'unsafe-inline'")))output.add(ScanSupport.finding("csp-unsafe-keyword","Info","Observation","Permissive CSP keyword observed","The policy contains unsafe-inline or unsafe-eval. Nonces, hashes, strict-dynamic and directive precedence can change its effect; this observation is not proof of bypass.",request,"","CSP: "+ScanSupport.trim(csp,300),baseline));
            String setCookie=baseline.responseHeaders==null?"":baseline.responseHeaders;
            for(String line:setCookie.split("\r?\n"))if(line.regionMatches(true,0,"Set-Cookie:",0,11)){
                String[] parts=line.substring(11).trim().split(";");Map<String,String> attributes=new LinkedHashMap<>();for(int i=1;i<parts.length;i++){int eq=parts[i].indexOf('=');attributes.put((eq<0?parts[i]:parts[i].substring(0,eq)).trim().toLowerCase(Locale.ROOT),eq<0?"":parts[i].substring(eq+1).trim());}
                String name=parts[0].split("=",2)[0].trim();
                if("none".equalsIgnoreCase(attributes.get("samesite"))&&!attributes.containsKey("secure"))output.add(ScanSupport.finding("cookie-samesite-none","Info","Observation","SameSite=None cookie without Secure","Modern browsers generally reject this combination. Review compatibility; this is not proof that a cross-site attack succeeds.",request,"","Cookie attributes: SameSite=None; Secure absent. Value omitted.",baseline));
                if((name.startsWith("__Secure-")&&!attributes.containsKey("secure"))||(name.startsWith("__Host-")&&(!attributes.containsKey("secure")||attributes.containsKey("domain")||!"/".equals(attributes.get("path")))))output.add(ScanSupport.finding("cookie-prefix-policy","Info","Observation","Cookie prefix requirements not observed","A __Secure- or __Host- cookie does not show the prefix's required attributes. Supporting browsers generally reject it rather than accept a weaker cookie. Cookie values are omitted.",request,"","Cookie name prefix: "+(name.startsWith("__Host-")?"__Host-":"__Secure-")+"; review Secure, Domain and Path attributes.",baseline));
                if(output.size()>100)break;
            }
        }
        if(enabled.contains("cache")){
            String cache=ScanSupport.header(baseline,"cache-control").toLowerCase(Locale.ROOT);
            boolean authorization=!request.header("Authorization").isEmpty(),authenticated=authorization||!request.header("Cookie").isEmpty();
            boolean restricted=Pattern.compile("(?:^|,)\\s*(?:private|no-store)\\s*(?:,|$)").matcher(cache).find();
            boolean shared=Pattern.compile("(?:^|,)\\s*public\\s*(?:,|$)").matcher(cache).find()||Pattern.compile("(?:^|,)\\s*s-maxage\\s*=\\s*\"?0*[1-9][0-9]*\"?\\s*(?:,|$)").matcher(cache).find();
            if(authenticated&&!restricted&&shared)output.add(ScanSupport.finding("authenticated-shared-cache",authorization?"Medium":"Info",authorization?"Tentative":"Observation","Shared-cache policy on a credential-bearing request","The request carried Cookie or Authorization, and the response advertised public or positive s-maxage caching. Authentication and data sensitivity are not established by header presence alone. Cache keys, Vary and application semantics determine sharing. No poisoning or cross-user retrieval was attempted.",request,"","Cache-Control: "+ScanSupport.trim(cache,300)+"; Vary: "+ScanSupport.header(baseline,"vary"),baseline));
            if(authenticated&&ScanSupport.html(baseline)&&!cache.contains("no-store")&&!cache.contains("private"))output.add(ScanSupport.finding("authenticated-cache-policy","Info","Observation","Credential-bearing response lacks explicit private/no-store","Some credential-bearing content is intentionally public or cacheable. Review sensitivity and effective browser/CDN caching rather than treating header absence as disclosure.",request,"","Cache-Control: "+(cache.isEmpty()?"absent":cache),baseline));
        }
        if(enabled.contains("disclosure")){
            Matcher key=PRIVATE_KEY.matcher(body);if(key.find())output.add(ScanSupport.finding("private-key-disclosure","High","Certain","Private-key block in response","A recognizable complete private-key PEM block was returned. It may be an intentional test fixture or encrypted key; determine ownership and sensitivity. Key material is omitted from this report.",request,"","[REDACTED PRIVATE KEY BLOCK] at response character "+key.start(),baseline));
            Matcher stack=STACK.matcher(body);if(stack.find())output.add(ScanSupport.finding("debug-disclosure","Low","Tentative","Stack trace or debug page signature","A framework/exception signature was observed. Review whether debug information exposes internal paths or configuration; signatures can occur in documentation and test pages.",request,"","Signature: "+ScanSupport.trim(stack.group(),120)+"; sample details omitted to avoid exposing secrets.",baseline));
        }
        if(enabled.contains("directory")&&ScanSupport.html(baseline)){
            String lower=body.toLowerCase(Locale.ROOT);
            if(lower.matches("(?s).*<(?:title|h1)[^>]*>\\s*(?:index of|directory listing for)[^<]*<.*")&&(lower.contains("parent directory")||lower.contains("href=\"../\"")||lower.contains("href='../'")))output.add(ScanSupport.finding("directory-listing","Low","Tentative","Directory index appears enabled","An index title and parent-directory link were observed. This may be an intended public listing; review files exposed by the endpoint.",request,"","Index title plus parent-directory indicator; no listed files were downloaded by this check.",baseline));
        }
        if(html!=null&&enabled.contains("csrf")){
            int forms=0;for(HtmlModel.Form form:html.forms){if(forms++>=20)break;String method=form.tag.attr("method");if(!method.equalsIgnoreCase("post"))continue;
                boolean token=false,password=false;for(HtmlModel.Tag field:form.fields){String name=field.attr("name");if(name.matches("(?i).*(?:csrf|xsrf|nonce|authenticity).*"))token=true;if(field.attr("type").equalsIgnoreCase("password"))password=true;}
                String action=form.tag.attr("action");if(!token)output.add(ScanSupport.finding("csrf-form-observation","Info","Observation","POST form without an obvious CSRF field","No familiar CSRF field name was found. SameSite cookies, Origin/Referer checks, custom headers and other protections may apply. The form was not submitted."+(password?" Password field values are not collected.":""),request,"form "+forms,"Method: POST; action: "+ScanSupport.trim(action.isEmpty()?"current URL":action,250),baseline));
            }
        }
        if(enabled.contains("dom")){
            String script=body;String type=ScanSupport.header(baseline,"content-type").toLowerCase(Locale.ROOT);
            if(html!=null){StringBuilder joined=new StringBuilder();for(int[] range:html.scripts)joined.append(body,range[0],range[1]).append('\n');script=joined.toString();}
            else if(!type.contains("javascript")&&!type.contains("ecmascript"))script="";
            Matcher source=DOM_SOURCE.matcher(script),sink=DOM_SINK.matcher(script);
            if(source.find()&&sink.find())output.add(ScanSupport.finding("dom-source-sink-observation","Info","Observation","DOM source and HTML/code sink patterns coexist","Source code contains both a browser-controlled source and a dangerous sink. This check does not execute JavaScript, establish dataflow or demonstrate DOM XSS. Review the relevant code manually.",request,"","Source pattern: "+source.group()+"; sink pattern: "+sink.group()+". Source values and surrounding secrets omitted.",baseline));
        }
    }
}
