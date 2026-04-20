package com.warzone.vpn;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 用户态 TCP 转发器
 * 将 TUN 接口的 TCP 包转为真实 Socket 连接，实现流量转发
 */
public class TcpForwarder {

    private static final String TAG = "TcpForwarder";

    // 连接表: key = "srcIP:srcPort->dstIP:dstPort"
    private final Map<String, TcpConnection> connections = new HashMap<>();
    private Selector selector;
    private volatile boolean running = false;

    public void start() throws IOException {
        selector = Selector.open();
        running = true;
        new Thread(this::selectLoop, "TcpForwarder").start();
    }

    public void stop() {
        running = false;
        if (selector != null) {
            try { selector.close(); } catch (IOException ignored) {}
        }
        for (TcpConnection conn : connections.values()) {
            conn.close();
        }
        connections.clear();
    }

    /**
     * 处理从 TUN 读到的 TCP 包
     * @param pkt 完整 IP 包
     * @param ipHeaderLen IP 头长度
     * @param tunOut TUN 的输出流
     */
    public void handlePacket(byte[] pkt, int ipHeaderLen, java.io.FileOutputStream tunOut) {
        int tcpOff = ipHeaderLen;
        if (pkt.length < tcpOff + 20) return;

        int srcPort = ((pkt[tcpOff] & 0xFF) << 8) | (pkt[tcpOff + 1] & 0xFF);
        int dstPort = ((pkt[tcpOff + 2] & 0xFF) << 8) | (pkt[tcpOff + 3] & 0xFF);
        long seqNum = ((long)(pkt[tcpOff + 4] & 0xFF) << 24) | ((pkt[tcpOff + 5] & 0xFF) << 16) |
                      ((pkt[tcpOff + 6] & 0xFF) << 8) | (pkt[tcpOff + 7] & 0xFF);
        long ackNum = ((long)(pkt[tcpOff + 8] & 0xFF) << 24) | ((pkt[tcpOff + 9] & 0xFF) << 16) |
                      ((pkt[tcpOff + 10] & 0xFF) << 8) | (pkt[tcpOff + 11] & 0xFF);
        int dataOffset = ((pkt[tcpOff + 12] >> 4) & 0xF) * 4;
        int flags = pkt[tcpOff + 13] & 0xFF;
        boolean syn = (flags & 0x02) != 0;
        boolean ack = (flags & 0x10) != 0;
        boolean fin = (flags & 0x01) != 0;
        boolean rst = (flags & 0x04) != 0;

        // 提取源/目标 IP
        String srcIp = (pkt[12] & 0xFF) + "." + (pkt[13] & 0xFF) + "." +
                       (pkt[14] & 0xFF) + "." + (pkt[15] & 0xFF);
        String dstIp = (pkt[16] & 0xFF) + "." + (pkt[17] & 0xFF) + "." +
                       (pkt[18] & 0xFF) + "." + (pkt[19] & 0xFF);
        String key = srcIp + ":" + srcPort + "->" + dstIp + ":" + dstPort;

        // 提取 TCP 数据
        int payloadStart = tcpOff + dataOffset;
        int payloadLen = pkt.length - payloadStart;
        byte[] payload = null;
        if (payloadLen > 0) {
            payload = new byte[payloadLen];
            System.arraycopy(pkt, payloadStart, payload, 0, payloadLen);
        }

        if (rst) {
            // RST：关闭连接
            TcpConnection conn = connections.remove(key);
            if (conn != null) conn.close();
            return;
        }

        if (syn && !ack) {
            // 新连接 SYN
            if (connections.containsKey(key)) {
                connections.get(key).close();
            }
            try {
                TcpConnection conn = new TcpConnection(key, dstIp, dstPort, tunOut,
                        pkt, ipHeaderLen, seqNum, selector);
                connections.put(key, conn);
                // 发送 SYN-ACK 回 TUN
                conn.sendSynAck();
                // 异步连接目标服务器
                conn.connectToServer();
            } catch (Exception e) {
                Log.w(TAG, "连接失败 " + dstIp + ":" + dstPort + " - " + e.getMessage());
                sendRst(pkt, ipHeaderLen, tunOut);
            }
            return;
        }

        TcpConnection conn = connections.get(key);
        if (conn == null) {
            // 未知连接的包，忽略
            return;
        }

        if (fin) {
            // FIN：关闭连接
            conn.handleFin(seqNum);
            connections.remove(key);
            return;
        }

        if (ack && payload != null && payload.length > 0) {
            // 有数据的 ACK：转发数据
            conn.handleData(payload, seqNum);
        } else if (ack) {
            // 纯 ACK：确认
            conn.handleAck(ackNum);
        }
    }

    /**
     * 定期处理 NIO 连接的响应
     */
    public void processResponses() {
        if (selector == null || !running) return;
        try {
            selector.select(5); // 非阻塞等待 5ms
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (key.isReadable()) {
                    TcpConnection conn = (TcpConnection) key.attachment();
                    if (conn != null) {
                        conn.readResponse();
                    }
                }
                if (key.isConnectable()) {
                    TcpConnection conn = (TcpConnection) key.attachment();
                    if (conn != null) {
                        conn.finishConnect();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }

        // 清理超时连接
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, TcpConnection>> it = connections.entrySet().iterator();
        while (it.hasNext()) {
            TcpConnection conn = it.next().getValue();
            if (now - conn.createdAt > 60000) { // 60秒超时
                conn.close();
                it.remove();
            }
        }
    }

    private void sendRst(byte[] pkt, int ipHeaderLen, java.io.FileOutputStream tunOut) {
        try {
            // 构造 RST 包
            byte[] rst = new byte[ipHeaderLen + 20];
            System.arraycopy(pkt, 0, rst, 0, Math.min(pkt.length, rst.length));
            // 交换 IP
            rst[12] = pkt[16]; rst[13] = pkt[17]; rst[14] = pkt[18]; rst[15] = pkt[19];
            rst[16] = pkt[12]; rst[17] = pkt[13]; rst[18] = pkt[14]; rst[19] = pkt[15];
            // 交换端口
            int off = ipHeaderLen;
            rst[off] = pkt[off + 2]; rst[off + 1] = pkt[off + 3];
            rst[off + 2] = pkt[off]; rst[off + 3] = pkt[off + 1];
            // RST flag
            rst[off + 13] = 0x04; // RST
            // Seq = 原始ACK
            rst[off + 4] = pkt[off + 8]; rst[off + 5] = pkt[off + 9];
            rst[off + 6] = pkt[off + 10]; rst[off + 7] = pkt[off + 11];
            // 长度
            rst[2] = 0; rst[3] = (byte)(ipHeaderLen + 20);

            tunOut.write(rst);
            tunOut.flush();
        } catch (Exception ignored) {}
    }
}
