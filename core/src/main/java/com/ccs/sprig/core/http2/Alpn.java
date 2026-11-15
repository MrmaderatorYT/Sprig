package com.ccs.sprig.core.http2;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Public ALPN APIs are available on Android 29+ and Java 9+. Older Android keeps HTTP/1. */
public final class Alpn {
    private Alpn() { }
    public static boolean configure(SSLParameters parameters, String... protocols) {
        try {
            Method method = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
            method.invoke(parameters, (Object) protocols);
            return true;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | UnsupportedOperationException unavailable) {
            return false;
        }
    }
    public static String selected(SSLEngine engine) { return selected(SSLEngine.class, engine); }
    public static String selected(SSLSocket socket) { return selected(SSLSocket.class, socket); }
    private static String selected(Class<?> type, Object instance) {
        try {
            Object value = type.getMethod("getApplicationProtocol").invoke(instance);
            return value instanceof String ? (String) value : "";
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | UnsupportedOperationException unavailable) {
            return "";
        }
    }
}
