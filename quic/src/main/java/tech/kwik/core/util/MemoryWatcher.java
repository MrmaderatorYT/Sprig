/*
 * Copyright © 2025, 2026 Peter Doornbosch
 *
 * This file is part of Kwik, an implementation of the QUIC protocol in Java.
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package tech.kwik.core.util;

import tech.kwik.core.log.Logger;

/**
 * Portable Android replacement for Kwik's optional JMX heap-warning logger.
 *
 * <p>Modified for Sprig on 2026-11-14: Android has no java.lang.management or
 * javax.management runtime. Heap/flow/queue budgets are owned by Sprig and
 * the QUIC receive-buffer limits, so this optional diagnostic hook registers
 * no listeners, creates no monitor threads, and performs no polling.
 * The original public constructor and constants are retained.
 */
public class MemoryWatcher {
    public static final int MIN_LOG_INTERVAL_SECONDS = 5;
    public static final int MIN_PERCENTAGE_DELTA = 3;

    /** Compatibility hook; monitoring is intentionally supplied by the embedding app. */
    public MemoryWatcher(int notificationThreshold, Logger log) {
        // No JMX on Android. Sprig's process-heap and flow budgets remain active.
    }
}
