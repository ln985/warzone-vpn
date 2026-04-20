package com.warzone.vpn;

import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地 HTTP 代理服务器
 * 需要在系统设置中手动配置代理为 127.0.0.1:8080
 * 或在 VPN 启动后通过系统代理设置指向此端口
 */
public class LocalProxyServer {

    private static final String TAG = "LocalProxy";
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    public LocalProxyServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running = true;

        executor.submit(() -> {
            Log.i(TAG, "代理服务器监听端口 " + port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(new ProxyHandler(client));
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept异常: " + e.getMessage());
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }
}
