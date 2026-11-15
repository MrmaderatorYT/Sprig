package com.ccs.sprig.core.scanner;

public final class Progress {
    public final int pages, requests, queued, issues;
    public final String state;
    Progress(int pages,int requests,int queued,int issues,String state) {
        this.pages=pages;this.requests=requests;this.queued=queued;this.issues=issues;this.state=state;
    }
}
