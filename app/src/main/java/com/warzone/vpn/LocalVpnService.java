package com.warzone.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class LocalVpnService extends VpnService {

    private static final String TAG = "LocalVPN";
    public static volatile boolean isRunning = false;
    public static volatile String currentAdcode = "";
    public static volatile String currentFullName = "";

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private LocalProxyServer proxyServer;
    private volatile boolean shouldStop = false;

    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final int VPN_PREFIX = 24;
    private static final int MTU = 1500;
    private static final String[] DNS_SERVERS = {"223.5.5.5", "114.114.114.114"};

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "disconnect".equals(intent.getStringExtra("action"))) {
            disconnect();
            return START_NOT_STICKY;
        }
        if (isRunning) disconnect();

        currentAdcode = intent != null ? intent.getStringExtra("adcode") : "";
        currentFullName = intent != null ? intent.getStringExtra("fullName") : "";
        Log.i(TAG, "启动VPN - " + currentFullName + " [" + currentAdcode + "]");
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        isRunning = true;
        shouldStop = false;
        try {
            Builder builder = new Builder()
                    .setSession("WarzoneVPN")
                    .addAddress(VPN_ADDRESS, VPN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("223.5.5.5")
                    .addDnsServer("114.114.114.114")
                    .setMtu(MTU)
                    .addDisallowedApplication(getPackageName());

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "无法建立 VPN 接口");
                stopSelf();
                return;
            }

            // 启动本地代理
            proxyServer = new LocalProxyServer(8080);
            try {
                proxyServer.start();
                Log.i(TAG, "代理服务器已启动: 127.0.0.1:8080");
            } catch (Exception e) {
                Log.w(TAG, "代理启动失败(不影响VPN): " + e.getMessage());
            }

            vpnThread = new Thread(this::trafficLoop, "VPN-Thread");
            vpnThread.start();
            Log.i(TAG, "VPN 已启动，DNS 代理就绪");
        } catch (Exception e) {
            Log.e(TAG, "启动失败: " + e.getMessage(), e);
            disconnect();
        }
    }

    /**
     * 主循环：从 TUN 读取所有 IP 包
     */
    private void trafficLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer packet = ByteBuffer.allocate(MTU);

        DatagramSocket dnsSocket = null;
        try {
            // DNS socket 不调用 protect()，走真实网络
            dnsSocket = new DatagramSocket();
            dnsSocket.setSoTimeout(5000);

            while (!shouldStop && isRunning) {
                int length;
                try {
                    length = in.read(packet.array());
                } catch (Exception e) {
                    if (!shouldStop) Log.w(TAG, "TUN读取失败: " + e.getMessage());
                    break;
                }

                if (length > 0) {
                    byte[] data = new byte[length];
                    System.arraycopy(packet.array(), 0, data, 0, length);
                    packet.clear();
                    processPacket(data, out, dnsSocket);
                } else {
                    try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "流量循环异常: " + e.getMessage());
        } finally {
            if (dnsSocket != null) dnsSocket.close();
        }
    }

    /**
     * 处理 IP 包
     */
    private void processPacket(byte[] pkt, FileOutputStream out, DatagramSocket dnsSocket) {
        if (pkt.length < 20) return;
        int version = (pkt[0] >> 4) & 0xF;
        if (version != 4) return;

        int ihl = (pkt[0] & 0xF) * 4;
        int protocol = pkt[9] & 0xFF;

        // 目标 IP
        int d1 = pkt[16] & 0xFF, d2 = pkt[17] & 0xFF,
            d3 = pkt[18] & 0xFF, d4 = pkt[19] & 0xFF;
        String dstIp = d1 + "." + d2 + "." + d3 + "." + d4;

        // 忽略 VPN 子网和回环
        if (d1 == 10 && d2 == 0 && d3 == 0) return;
        if (d1 == 127) return;

        if (protocol == 17 && pkt.length > ihl + 8) {
            // UDP
            int dstPort = ((pkt[ihl + 2] & 0xFF) << 8) | (pkt[ihl + 3] & 0xFF);
            if (dstPort == 53) {
                forwardDns(pkt, ihl, dnsSocket, out);
            }
        }
        // TCP 流量（80/443）需要用户态TCP栈才能转发到本地代理
        // 这里先透传不处理，代理功能通过系统代理设置实现
    }

    /**
     * DNS 查询转发
     * 从 TUN 拦截 DNS 请求 → 转发到真实 DNS → 将响应写回 TUN
     */
    private void forwardDns(byte[] pkt, int ihl, DatagramSocket dnsSocket, FileOutputStream out) {
        try {
            // 提取 DNS 查询数据（跳过 IP 头 + UDP 头）
            int dnsStart = ihl + 8;
            int dnsLen = pkt.length - dnsStart;
            if (dnsLen <= 0 || dnsLen > 512) return;

            byte[] dnsQuery = new byte[dnsLen];
            System.arraycopy(pkt, dnsStart, dnsQuery, 0, dnsLen);

            // 依次尝试 DNS 服务器
            for (String server : DNS_SERVERS) {
                try {
                    InetAddress addr = InetAddress.getByName(server);
                    DatagramPacket req = new DatagramPacket(dnsQuery, dnsLen, addr, 53);
                    dnsSocket.send(req);

                    byte[] respBuf = new byte[512];
                    DatagramPacket resp = new DatagramPacket(respBuf, respBuf.length);
                    dnsSocket.receive(resp);

                    if (resp.getLength() > 0) {
                        writeDnsResponse(pkt, ihl, respBuf, resp.getLength(), out);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "DNS " + server + " 失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "DNS转发异常: " + e.getMessage());
        }
    }

    /**
     * 构造 DNS 响应包，写回 VPN TUN 接口
     * 交换 IP 源/目标，交换 UDP 端口，附上 DNS 响应数据
     */
    private void writeDnsResponse(byte[] reqPkt, int ihl, byte[] dnsResp, int dnsRespLen,
                                   FileOutputStream out) throws Exception {
        int udpLen = 8 + dnsRespLen;
        int totalLen = ihl + udpLen;
        byte[] pkt = new byte[totalLen];

        // 复制原始 IP 头
        System.arraycopy(reqPkt, 0, pkt, 0, ihl);

        // 交换源/目标 IP
        pkt[12] = reqPkt[16]; pkt[13] = reqPkt[17];
        pkt[14] = reqPkt[18]; pkt[15] = reqPkt[19];
        pkt[16] = reqPkt[12]; pkt[17] = reqPkt[13];
        pkt[18] = reqPkt[14]; pkt[19] = reqPkt[15];

        // IP 总长度
        pkt[2] = (byte) ((totalLen >> 8) & 0xFF);
        pkt[3] = (byte) (totalLen & 0xFF);
        pkt[8] = 64; // TTL

        // IP 校验和
        pkt[10] = 0; pkt[11] = 0;
        int cksum = ipChecksum(pkt, 0, ihl);
        pkt[10] = (byte) (cksum >> 8);
        pkt[11] = (byte) (cksum & 0xFF);

        // UDP 头：交换端口
        int off = ihl;
        pkt[off]     = reqPkt[off + 2]; // src = 原始 dst
        pkt[off + 1] = reqPkt[off + 3];
        pkt[off + 2] = reqPkt[off];     // dst = 原始 src
        pkt[off + 3] = reqPkt[off + 1];
        pkt[off + 4] = (byte) (udpLen >> 8);
        pkt[off + 5] = (byte) (udpLen & 0xFF);
        pkt[off + 6] = 0; // UDP 校验和（可选）
        pkt[off + 7] = 0;

        // DNS 响应数据
        System.arraycopy(dnsResp, 0, pkt, off + 8, dnsRespLen);

        out.write(pkt);
        out.flush();
    }

    private int ipChecksum(byte[] data, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len - 1; i += 2)
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        if (len % 2 == 1)
            sum += (data[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0)
            sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private void disconnect() {
        isRunning = false;
        shouldStop = true;
        if (proxyServer != null) {
            proxyServer.stop();
            proxyServer = null;
        }
        if (vpnThread != null) {
            vpnThread.interrupt();
            try { vpnThread.join(2000); } catch (InterruptedException ignored) {}
            vpnThread = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }
        Log.i(TAG, "VPN 已断开");
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    @Override public void onRevoke() { disconnect(); super.onRevoke(); }
}
