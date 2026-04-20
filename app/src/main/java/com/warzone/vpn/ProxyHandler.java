package com.warzone.vpn;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

/**
 * HTTP 代理处理器
 * 拦截 HTTP 请求，注入地区头后转发
 */
public class ProxyHandler implements Runnable {

    private static final String TAG = "ProxyHandler";
    private final Socket client;

    public ProxyHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn));

            // 读取请求行
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            // 读取请求头
            StringBuilder headerBuilder = new StringBuilder();
            String host = null;
            int port = 80;
            boolean isConnect = requestLine.startsWith("CONNECT");
            String line;

            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("host:")) {
                    String hostVal = line.substring(5).trim();
                    if (hostVal.contains(":")) {
                        String[] parts = hostVal.split(":");
                        host = parts[0].trim();
                        port = Integer.parseInt(parts[1].trim());
                    } else {
                        host = hostVal;
                    }
                }
                headerBuilder.append(line).append("\r\n");
            }

            if (host == null) {
                client.close();
                return;
            }

            if (isConnect) {
                // HTTPS 隧道
                handleConnect(clientIn, clientOut, host, port);
            } else {
                // HTTP 请求
                handleHttp(requestLine, headerBuilder.toString(), clientIn, clientOut, host, port);
            }

        } catch (Exception e) {
            Log.w(TAG, "处理异常: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void handleConnect(InputStream clientIn, OutputStream clientOut,
                                String host, int port) throws Exception {
        // HTTPS: 建立隧道
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
        clientOut.flush();

        Socket remote = new Socket(host, port);
        pipe(clientIn, remote.getOutputStream(), remote.getInputStream(), clientOut);
    }

    private void handleHttp(String requestLine, String originalHeaders,
                             InputStream clientIn, OutputStream clientOut,
                             String host, int port) throws Exception {
        // 注入地区头
        StringBuilder modified = new StringBuilder();
        modified.append(requestLine).append("\r\n");

        String adcode = LocalVpnService.currentAdcode;
        String fullName = LocalVpnService.currentFullName;
        modified.append("X-Adcode: ").append(adcode).append("\r\n");
        modified.append("X-Region: ").append(fullName).append("\r\n");
        modified.append("X-Location-Code: ").append(adcode).append("\r\n");
        modified.append("X-Forwarded-Region: ").append(fullName).append("\r\n");

        // 追加原始头（去掉重复的地区头）
        for (String h : originalHeaders.split("\r\n")) {
            if (h.isEmpty()) continue;
            String lower = h.toLowerCase();
            if (!lower.startsWith("x-adcode:") &&
                !lower.startsWith("x-region:") &&
                !lower.startsWith("x-location-code:") &&
                !lower.startsWith("x-forwarded-region:") &&
                !lower.startsWith("proxy-connection:")) {
                modified.append(h).append("\r\n");
            }
        }
        modified.append("Connection: close\r\n\r\n");

        // 转发到目标
        Socket remote = new Socket(host, port);
        remote.getOutputStream().write(modified.toString().getBytes("UTF-8"));

        // 转发请求体
        for (String h : originalHeaders.split("\r\n")) {
            if (h.toLowerCase().startsWith("content-length:")) {
                int len = Integer.parseInt(h.substring(15).trim());
                byte[] body = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = clientIn.read(body, read, len - read);
                    if (n == -1) break;
                    read += n;
                }
                remote.getOutputStream().write(body);
                break;
            }
        }
        remote.getOutputStream().flush();

        // 转发响应
        pipeResponse(remote.getInputStream(), clientOut);
        remote.close();

        Log.d(TAG, "劫持: " + host + " -> " + adcode);
    }

    private void pipe(InputStream in1, OutputStream out1,
                      InputStream in2, OutputStream out2) {
        Thread t1 = new Thread(() -> copy(in1, out1));
        Thread t2 = new Thread(() -> copy(in2, out2));
        t1.start();
        t2.start();
        try { t1.join(30000); t2.join(30000); } catch (InterruptedException ignored) {}
    }

    private void copy(InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                out.flush();
            }
        } catch (Exception ignored) {}
    }

    private void pipeResponse(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
            out.flush();
        }
    }
}
