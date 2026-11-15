package com.ccs.sprig.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionStoreTest {
    @Test public void evictsOldestByCountAndMemory() {
        SessionStore store = new SessionStore(2, 8);
        for (int i=1;i<=3;i++) { Exchange e=new Exchange();e.id=i;e.requestBody=new byte[]{(byte)i};store.record(e); }
        assertEquals(2,store.size());assertEquals(3,store.snapshot().get(0).id);
        Exchange huge=new Exchange();huge.id=4;huge.responseBody=new byte[20];store.record(huge);
        assertEquals(0,store.size());assertEquals(0,store.memoryBytes());
    }
    @Test public void ownsCapturedBytesAndReplacesWithoutDoubleCounting() {
        SessionStore store = new SessionStore(10,1024);
        Exchange e = new Exchange();e.id=1;e.requestBody=new byte[]{42};store.record(e);e.requestBody[0]=0;
        assertEquals(42,store.snapshot().get(0).requestBody[0]);
        e.requestBody=new byte[2];store.record(e);assertEquals(2,store.memoryBytes());assertEquals(1,store.size());
    }
}
