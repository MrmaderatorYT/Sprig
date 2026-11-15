package com.ccs.sprig.core.proxy;

import java.net.Socket;

/** Excludes upstream sockets from the local VPN before they connect. */
public interface SocketProtector {
    boolean protect(Socket socket);
}
