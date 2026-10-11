package com.nexus.screenshot.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Chromium 的兜底拒绝代理：只接受本机连接后关闭，绝不转发目标请求。 */
final class BrowserNetworkGuard implements AutoCloseable {
    private final ServerSocket server = new ServerSocket();
    private final Thread worker;

    BrowserNetworkGuard() throws IOException {
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        worker = Thread.ofVirtual().name("screenshot-browser-deny-proxy").start(() -> {
            while (!server.isClosed()) {
                try (Socket ignored = server.accept()) {
                    // 不解析 CONNECT、不解析 URL、不访问任何远端。
                } catch (IOException exception) { return; }
            }
        });
    }

    String proxyUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }

    @Override
    public void close() throws IOException {
        server.close();
        try { worker.join(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
