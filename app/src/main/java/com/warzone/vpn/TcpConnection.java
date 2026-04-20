package com.warzone.vpn;

import android.util.Log;

import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * 单个 TCP 连接的状态管理
 * 在 TUN 客户端和真实服务器之间转发数据
 */
public class TcpConnection {

    private static final String TAG = "TcpConn";
    private static final int WINDOW_SIZE = 65535;

    private final String key;
    private final String dstIp;
    private final int dstPort;
    private final FileOutputStream tunOut;
    private final Selector selector;
    private SocketChannel remoteChannel;

    // TCP 序列号
    private long clientSeq;    // 客户端发送的最新序列号
    private long serverSeq;    // 我们告诉客户端的序列号
    private long myAckNum;     // 我们发给客户端的 ACK
    private long remoteSeq;    // 远端服务器的序列号

    // 原始客户端 IP 包信息（用于构造响应包）
    private final byte[] clientIpTemplate;
    public final long createdAt;
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private SelectionKey selectionKey;

    // HTTP 检测
    private boolean isHttp = false;
    private boolean headerInjected = false;
    private byte[] pendingData = null;

    public TcpConnection(String key, String dstIp, int dstPort,
                          FileOutputStream tunOut, byte[] clientPkt,
                          int ipHeaderLen, long clientSeq, Selector selector) {
        this.key = key;
        this.dstIp = dstIp;
        this.dstPort = dstPort;
        this.tunOut = tunOut;
        this.selector = selector;
        this.clientSeq = clientSeq;
        this.serverSeq = (long)(Math.random() * Integer.MAX_VALUE) & 0xFFFFFFFFL;
        this.myAckNum = clientSeq + 1; // SYN 占一个序号
        this.createdAt = System.currentTimeMillis();
        this.isHttp = (dstPort == 80);

        // 保存客户端 IP 头模板（用于构造响应包）
        clientIpTemplate = new byte[ipHeaderLen + 20];
        int copyLen = Math.min(clientPkt.length, clientIpTemplate.length);
        System.arraycopy(clientPkt, 0, clientIpTemplate, 0, copyLen);
    }

    /**
     * 发送 SYN-ACK 回 TUN（确认客户端的 SYN）
     */
    public void sendSynAck() {
        try {
            byte[] pkt = buildResponsePacket(null, 0, 0x12, serverSeq, myAckNum); // SYN+ACK
            tunOut.write(pkt);
            tunOut.flush();
            serverSeq++; // SYN 占一个序号
        } catch (Exception e) {
            Log.w(TAG, "SYN-ACK 失败: " + e.getMessage());
        }
    }

    /**
     * 异步连接到目标服务器
     */
    public void connectToServer() throws Exception {
        remoteChannel = SocketChannel.open();
        remoteChannel.configureBlocking(false);
        remoteChannel.connect(new InetSocketAddress(dstIp, dstPort));
        selectionKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ, this);
    }

    /**
     * 连接完成回调
     */
    public void finishConnect() {
        try {
            if (remoteChannel.finishConnect()) {
                connected = true;
                // 发送 ACK 确认连接建立
                sendAck(myAckNum, serverSeq);
                // 如果有待发数据，发送
                if (pendingData != null) {
                    forwardToServer(pendingData);
                    pendingData = null;
                }
                // 注册读事件
                selectionKey.interestOps(SelectionKey.OP_READ);
            }
        } catch (Exception e) {
            Log.w(TAG, "连接失败: " + e.getMessage());
            close();
        }
    }

    /**
     * 处理客户端发来的数据
     */
    public void handleData(byte[] data, long seq) {
        myAckNum = seq + data.length;

        // 发送 ACK
        sendAck(myAckNum, serverSeq);

        if (isHttp && !headerInjected) {
            // HTTP 数据，注入地区头
            byte[] modified = injectHttpHeaders(data);
            headerInjected = true;
            if (connected) {
                forwardToServer(modified);
            } else {
                pendingData = modified;
            }
        } else {
            if (connected) {
                forwardToServer(data);
            } else {
                pendingData = data;
            }
        }
    }

    /**
     * 处理客户端 ACK
     */
    public void handleAck(long ackNum) {
        // 确认已发送的数据
    }

    /**
     * 处理客户端 FIN
     */
    public void handleFin(long seq) {
        myAckNum = seq + 1;
        sendAck(myAckNum, serverSeq);
        close();
    }

    /**
     * 从远端服务器读取响应，转发给 TUN 客户端
     */
    public void readResponse() {
        if (!connected || closed) return;
        try {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int len = remoteChannel.read(buf);
            if (len > 0) {
                byte[] data = new byte[len];
                buf.flip();
                buf.get(data);
                sendDataToClient(data);
            } else if (len == -1) {
                // 服务器关闭连接
                sendFinToClient();
                close();
            }
        } catch (Exception e) {
            close();
        }
    }

    /**
     * 转发数据到目标服务器
     */
    private void forwardToServer(byte[] data) {
        try {
            remoteChannel.write(ByteBuffer.wrap(data));
        } catch (Exception e) {
            Log.w(TAG, "转发失败: " + e.getMessage());
            close();
        }
    }

    /**
     * 发送数据回 TUN 客户端
     */
    private void sendDataToClient(byte[] data) {
        try {
            byte[] pkt = buildResponsePacket(data, data.length, 0x18, serverSeq, myAckNum); // PSH+ACK
            tunOut.write(pkt);
            tunOut.flush();
            serverSeq += data.length;
        } catch (Exception e) {
            Log.w(TAG, "响应失败: " + e.getMessage());
        }
    }

    /**
     * 发送 ACK
     */
    private void sendAck(long ackNum, long seqNum) {
        try {
            byte[] pkt = buildResponsePacket(null, 0, 0x10, seqNum, ackNum); // ACK
            tunOut.write(pkt);
            tunOut.flush();
        } catch (Exception e) {
            Log.w(TAG, "ACK 失败: " + e.getMessage());
        }
    }

    /**
     * 发送 FIN 给客户端
     */
    private void sendFinToClient() {
        try {
            byte[] pkt = buildResponsePacket(null, 0, 0x11, serverSeq, myAckNum); // FIN+ACK
            tunOut.write(pkt);
            tunOut.flush();
            serverSeq++;
        } catch (Exception ignored) {}
    }

    /**
     * 构造响应 IP+TCP 包
     */
    private byte[] buildResponsePacket(byte[] payload, int payloadLen, int tcpFlags,
                                        long seqNum, long ackNum) {
        int ipHeaderLen = 20;
        int tcpHeaderLen = 20;
        int totalLen = ipHeaderLen + tcpHeaderLen + payloadLen;
        byte[] pkt = new byte[totalLen];

        // === IP 头 ===
        pkt[0] = 0x45; // IPv4, header length 20
        pkt[1] = 0x00; // TOS
        pkt[2] = (byte) ((totalLen >> 8) & 0xFF);
        pkt[3] = (byte) (totalLen & 0xFF);
        pkt[4] = 0x00; pkt[5] = 0x00; // ID
        pkt[6] = 0x40; pkt[7] = 0x00; // Flags: Don't Fragment
        pkt[8] = 64; // TTL
        pkt[9] = 6; // TCP
        pkt[10] = 0; pkt[11] = 0; // Checksum (calculated later)

        // 交换 IP（响应：源=客户端的目标，目标=客户端的源）
        pkt[12] = clientIpTemplate[16]; pkt[13] = clientIpTemplate[17];
        pkt[14] = clientIpTemplate[18]; pkt[15] = clientIpTemplate[19];
        pkt[16] = clientIpTemplate[12]; pkt[17] = clientIpTemplate[13];
        pkt[18] = clientIpTemplate[14]; pkt[19] = clientIpTemplate[15];

        // === TCP 头 ===
        int off = ipHeaderLen;
        // 交换端口
        pkt[off] = clientIpTemplate[off + 2];     // src port = 原始 dst port
        pkt[off + 1] = clientIpTemplate[off + 3];
        pkt[off + 2] = clientIpTemplate[off];      // dst port = 原始 src port
        pkt[off + 3] = clientIpTemplate[off + 1];

        // Seq number
        pkt[off + 4] = (byte) ((seqNum >> 24) & 0xFF);
        pkt[off + 5] = (byte) ((seqNum >> 16) & 0xFF);
        pkt[off + 6] = (byte) ((seqNum >> 8) & 0xFF);
        pkt[off + 7] = (byte) (seqNum & 0xFF);

        // ACK number
        pkt[off + 8] = (byte) ((ackNum >> 24) & 0xFF);
        pkt[off + 9] = (byte) ((ackNum >> 16) & 0xFF);
        pkt[off + 10] = (byte) ((ackNum >> 8) & 0xFF);
        pkt[off + 11] = (byte) (ackNum & 0xFF);

        // Data offset (5 * 4 = 20 bytes), reserved
        pkt[off + 12] = 0x50;
        // Flags
        pkt[off + 13] = (byte) tcpFlags;

        // Window size
        pkt[off + 14] = (byte) ((WINDOW_SIZE >> 8) & 0xFF);
        pkt[off + 15] = (byte) (WINDOW_SIZE & 0xFF);

        // Checksum (0 for now)
        pkt[off + 16] = 0;
        pkt[off + 17] = 0;
        // Urgent pointer
        pkt[off + 18] = 0;
        pkt[off + 19] = 0;

        // Payload
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, 0, pkt, off + tcpHeaderLen, payloadLen);
        }

        // IP checksum
        pkt[10] = 0; pkt[11] = 0;
        int ipCksum = calcChecksum(pkt, 0, ipHeaderLen);
        pkt[10] = (byte) (ipCksum >> 8);
        pkt[11] = (byte) (ipCksum & 0xFF);

        // TCP checksum (pseudo header + TCP header + data)
        int tcpCksum = calcTcpChecksum(pkt, ipHeaderLen, tcpHeaderLen + payloadLen);
        pkt[off + 16] = (byte) (tcpCksum >> 8);
        pkt[off + 17] = (byte) (tcpCksum & 0xFF);

        return pkt;
    }

    /**
     * 注入 HTTP 地区头
     */
    private byte[] injectHttpHeaders(byte[] data) {
        String text = new String(data);
        String adcode = LocalVpnService.currentAdcode;
        String fullName = LocalVpnService.currentFullName;

        int headerEnd = text.indexOf("\r\n\r\n");
        if (headerEnd < 0) return data;

        String headers = text.substring(0, headerEnd);
        String body = text.substring(headerEnd);

        StringBuilder sb = new StringBuilder();
        // 请求行
        int firstLine = headers.indexOf("\r\n");
        if (firstLine > 0) {
            sb.append(headers, 0, firstLine).append("\r\n");
            sb.append("X-Adcode: ").append(adcode).append("\r\n");
            sb.append("X-Region: ").append(fullName).append("\r\n");
            sb.append("X-Location-Code: ").append(adcode).append("\r\n");
            sb.append("X-Forwarded-Region: ").append(fullName).append("\r\n");
            sb.append(headers.substring(firstLine + 2));
        } else {
            sb.append(headers);
        }
        sb.append(body);

        return sb.toString().getBytes();
    }

    private int calcChecksum(byte[] data, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len - 1; i += 2)
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        if (len % 2 == 1)
            sum += (data[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0)
            sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private int calcTcpChecksum(byte[] pkt, int tcpOff, int tcpLen) {
        // Pseudo header
        int sum = 0;
        // Src IP
        sum += ((pkt[12] & 0xFF) << 8) | (pkt[13] & 0xFF);
        sum += ((pkt[14] & 0xFF) << 8) | (pkt[15] & 0xFF);
        // Dst IP
        sum += ((pkt[16] & 0xFF) << 8) | (pkt[17] & 0xFF);
        sum += ((pkt[18] & 0xFF) << 8) | (pkt[19] & 0xFF);
        // Protocol
        sum += 6;
        // TCP Length
        sum += tcpLen;

        // TCP segment
        for (int i = tcpOff; i < tcpOff + tcpLen - 1; i += 2)
            sum += ((pkt[i] & 0xFF) << 8) | (pkt[i + 1] & 0xFF);
        if (tcpLen % 2 == 1)
            sum += (pkt[tcpOff + tcpLen - 1] & 0xFF) << 8;

        while ((sum >> 16) != 0)
            sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    public void close() {
        closed = true;
        if (remoteChannel != null) {
            try { remoteChannel.close(); } catch (Exception ignored) {}
        }
    }
}
