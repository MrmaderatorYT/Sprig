package com.ccs.sprig.core.scanner;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit user-selected scan scope. Settings are copied and validated when a run begins. */
public final class ScanConfig {
    public final String seedUrl;
    public final byte[] capturedGetRequest;
    /** General captured request alias. POST requires allowStatefulTesting; old constructor/API remains valid. */
    public final byte[] capturedRequest;
    public enum InsertionType { QUERY, HEADER, FORM, JSON }
    public int maxPages = 20;
    public int maxRequests = 150;
    public int concurrency = 2;
    public long delayMillis = 250;
    public int maxDepth = 3;
    public boolean crawl = true;
    public boolean activeChecks = true;
    public boolean allowStatefulTesting = false;
    public int maxInsertionPoints = 6;
    public int maxProbesPerCheck = 24;
    public final Set<String> enabledChecks = new LinkedHashSet<>(CheckRegistry.defaultIds());
    public final Set<InsertionType> insertionPoints = EnumSet.of(InsertionType.QUERY);
    public final Set<String> headerNames = new LinkedHashSet<>();
    public String collaboratorPayload = "";

    public ScanConfig(String seedUrl) { this(seedUrl, null); }
    public ScanConfig(String seedUrl, byte[] capturedGetRequest) {
        this.seedUrl = seedUrl;
        this.capturedGetRequest = capturedGetRequest == null ? null : capturedGetRequest.clone();
        this.capturedRequest = this.capturedGetRequest;
        headerNames.add("X-Sprig-Input");
    }
}
