package com.warzone.vpn;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地 HTTP 代理服务器
 * 拦截 HTTP/HTTPS 流量并根据选中的地区修改请求头
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
            Log.i(TAG, "代理服务器启动在端口 " + port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) {
                        Log.w(TAG, "接受连接异常: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 处理客户端连接
     */
    private void handleClient(Socket client) {
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            // 读取请求行和头部
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn));
            StringBuilder requestBuilder = new StringBuilder();
            String line;
            String requestLine = reader.readLine();

            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            requestBuilder.append(requestLine).append("\r\n");

            // 解析目标主机和端口
            String host = null;
            int targetPort = 80;
            boolean isConnect = requestLine.startsWith("CONNECT");

            // 读取所有请求头
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("host:")) {
                    String hostHeader = line.substring(5).trim();
                    if (hostHeader.contains(":")) {
                        String[] parts = hostHeader.split(":");
                        host = parts[0].trim();
                        targetPort = Integer.parseInt(parts[1].trim());
                    } else {
                        host = hostHeader;
                    }
                }
                requestBuilder.append(line).append("\r\n");
            }
            requestBuilder.append("\r\n"); // 空行结束头部

            if (host == null) {
                client.close();
                return;
            }

            // CONNECT 方法（HTTPS 隧道）
            if (isConnect) {
                handleHttpsTunnel(client, clientIn, clientOut, host, targetPort);
                return;
            }

            // HTTP 请求：修改请求头后转发
            handleHttpRequest(client, clientIn, clientOut, host, targetPort,
                    requestBuilder.toString());

        } catch (Exception e) {
            Log.w(TAG, "处理客户端异常: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 处理 HTTPS CONNECT 隧道
     * 建立 TCP 隧道，原始加密流量透传
     */
    private void handleHttpsTunnel(Socket client, InputStream clientIn,
                                    OutputStream clientOut,
                                    String host, int port) throws Exception {
        // 发送 200 Connection Established
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
        clientOut.flush();

        // 连接目标服务器
        Socket remote = new Socket(host, port);
        InputStream remoteIn = remote.getInputStream();
        OutputStream remoteOut = remote.getOutputStream();

        // 双向转发数据
        Thread upstream = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int len;
                while ((len = clientIn.read(buf)) != -1) {
                    remoteOut.write(buf, 0, len);
                    remoteOut.flush();
                }
            } catch (IOException ignored) {}
        });

        Thread downstream = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int len;
                while ((len = remoteIn.read(buf)) != -1) {
                    clientOut.write(buf, 0, len);
                    clientOut.flush();
                }
            } catch (IOException ignored) {}
        });

        upstream.start();
        downstream.start();
        upstream.join(30000);
        downstream.join(30000);

        remote.close();
    }

    /**
     * 处理 HTTP 请求：注入地区相关请求头后转发
     */
    private void handleHttpRequest(Socket client, InputStream clientIn,
                                    OutputStream clientOut,
                                    String host, int port,
                                    String originalHeaders) throws Exception {
        // 修改请求头：注入地区信息
        String modifiedHeaders = injectRegionHeaders(originalHeaders);

        // 连接目标服务器
        Socket remote = new Socket(host, port);
        InputStream remoteIn = remote.getInputStream();
        OutputStream remoteOut = remote.getOutputStream();

        // 发送修改后的请求头
        remoteOut.write(modifiedHeaders.getBytes("UTF-8"));
        remoteOut.flush();

        // 转发请求体（如果有 Content-Length）
        int contentLength = getContentLength(originalHeaders);
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = clientIn.read(body, totalRead, contentLength - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            remoteOut.write(body);
            remoteOut.flush();
        }

        // 转发响应给客户端
        byte[] buf = new byte[8192];
        int len;
        while ((len = remoteIn.read(buf)) != -1) {
            clientOut.write(buf, 0, len);
            clientOut.flush();
        }

        remote.close();

        Log.d(TAG, "劫持: " + host + " -> 注入地区: " + LocalVpnService.currentAdcode);
    }

    /**
     * 注入地区相关请求头
     */
    private String injectRegionHeaders(String originalHeaders) {
        String adcode = LocalVpnService.currentAdcode;
        String fullName = LocalVpnService.currentFullName;

        StringBuilder sb = new StringBuilder();

        // 在请求头第一行之后插入地区头
        String[] lines = originalHeaders.split("\r\n");
        if (lines.length > 0) {
            sb.append(lines[0]).append("\r\n");

            // 注入地区伪装头
            sb.append("X-Adcode: ").append(adcode).append("\r\n");
            sb.append("X-Region: ").append(fullName).append("\r\n");
            sb.append("X-Location-Code: ").append(adcode).append("\r\n");
            sb.append("X-Forwarded-Region: ").append(fullName).append("\r\n");

            // 追加其余请求头
            for (int i = 1; i < lines.length; i++) {
                String lower = lines[i].toLowerCase();
                // 覆盖已有的地区相关头
                if (!lower.startsWith("x-adcode:") &&
                    !lower.startsWith("x-region:") &&
                    !lower.startsWith("x-location-code:") &&
                    !lower.startsWith("x-forwarded-region:")) {
                    sb.append(lines[i]).append("\r\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 从请求头中提取 Content-Length
     */
    private int getContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }
}
