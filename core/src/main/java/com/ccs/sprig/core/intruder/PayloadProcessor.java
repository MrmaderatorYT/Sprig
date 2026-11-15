package com.ccs.sprig.core.intruder;

import java.io.IOException;

@FunctionalInterface
public interface PayloadProcessor {
    byte[] apply(byte[] input) throws IOException;
    /** Conservative retained state estimate; custom processors must include their captured state. */
    default long estimatedBytes(){return 128;}
}
