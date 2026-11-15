package com.ccs.sprig.ui;
import com.ccs.sprig.R;
import com.ccs.sprig.core.scanner.CheckRegistry;
final class ScannerLabels {
    private ScannerLabels() {}
    static String title(CheckRegistry.Definition check) {
        switch(check.id) {
            case "headers": return UiText.get(R.string.check_headers_title);
            case "reflection": return UiText.get(R.string.check_reflection_title);
            case "sql-error": return UiText.get(R.string.check_sql_error_title);
            case "redirect": return UiText.get(R.string.check_redirect_title);
            case "cors": return UiText.get(R.string.check_cors_title);
            case "template": return UiText.get(R.string.check_template_title);
            case "crlf": return UiText.get(R.string.check_crlf_title);
            case "host-header": return UiText.get(R.string.check_host_header_title);
            case "methods": return UiText.get(R.string.check_methods_title);
            case "sql-boolean": return UiText.get(R.string.check_sql_boolean_title);
            case "cache": return UiText.get(R.string.check_cache_title);
            case "disclosure": return UiText.get(R.string.check_disclosure_title);
            case "directory": return UiText.get(R.string.check_directory_title);
            case "csrf": return UiText.get(R.string.check_csrf_title);
            case "dom": return UiText.get(R.string.check_dom_title);
            case "oast": return UiText.get(R.string.check_oast_title);
            default: return check.title;
        }
    }
    static String description(CheckRegistry.Definition check) {
        switch(check.id) {
            case "headers": return UiText.get(R.string.check_headers_description);
            case "reflection": return UiText.get(R.string.check_reflection_description);
            case "sql-error": return UiText.get(R.string.check_sql_error_description);
            case "redirect": return UiText.get(R.string.check_redirect_description);
            case "cors": return UiText.get(R.string.check_cors_description);
            case "template": return UiText.get(R.string.check_template_description);
            case "crlf": return UiText.get(R.string.check_crlf_description);
            case "host-header": return UiText.get(R.string.check_host_header_description);
            case "methods": return UiText.get(R.string.check_methods_description);
            case "sql-boolean": return UiText.get(R.string.check_sql_boolean_description);
            case "cache": return UiText.get(R.string.check_cache_description);
            case "disclosure": return UiText.get(R.string.check_disclosure_description);
            case "directory": return UiText.get(R.string.check_directory_description);
            case "csrf": return UiText.get(R.string.check_csrf_description);
            case "dom": return UiText.get(R.string.check_dom_description);
            case "oast": return UiText.get(R.string.check_oast_description);
            default: return check.description;
        }
    }
}
