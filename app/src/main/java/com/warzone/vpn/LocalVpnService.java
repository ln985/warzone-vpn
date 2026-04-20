package com.warzone.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;

public class LocalVpnService extends VpnService {

    private static final String TAG = "LocalVPN";
    public static volatile boolean isRunning = false;
    public static volatile String currentAdcode = "";
    public static volatile String currentFullName = "";

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private LocalProxyServer proxyServer;
    private Selector selector;

    // VPN 虚拟网络参数
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final int VPN_PREFIX = 24;
    private static final int MTU = 1500;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "disconnect".equals(intent.getStringExtra("action"))) {
            disconnect();
            return START_NOT_STICKY;
        }

        if (isRunning) {
            disconnect();
        }

        String province = intent != null ? intent.getStringExtra("province") : "";
        String city = intent != null ? intent.getStringExtra("city") : "";
        String district = intent != null ? intent.getStringExtra("district") : "";
        String adcode = intent != null ? intent.getStringExtra("adcode") : "";
        String fullName = intent != null ? intent.getStringExtra("fullName") : "";

        currentAdcode = adcode;
        currentFullName = fullName;

        Log.i(TAG, "启动VPN - " + fullName + " [" + adcode + "]");
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        isRunning = true;

        try {
            // 1. 建立 VPN TUN 接口
            Builder builder = new Builder()
                    .setSession("WarzoneVPN")
                    .addAddress(VPN_ADDRESS, VPN_PREFIX)
                    .addRoute("0.0.0.0", 0)       // 拦截所有 IPv4 流量
                    .addDnsServer("223.5.5.5")     // 阿里 DNS
                    .addDnsServer("114.114.114.114")
                    .setMtu(MTU);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "无法建立 VPN 接口");
                stopSelf();
                return;
            }

            // 2. 启动本地代理服务器
            proxyServer = new LocalProxyServer(8080);
            proxyServer.start();
            Log.i(TAG, "本地代理已启动: 127.0.0.1:8080");

            // 3. 启动流量处理线程
            selector = Selector.open();
            vpnThread = new Thread(this::processTraffic, "VPN-Thread");
            vpnThread.start();

            Log.i(TAG, "VPN 接口已建立，流量劫持已开启");

        } catch (Exception e) {
            Log.e(TAG, "启动 VPN 失败: " + e.getMessage(), e);
            disconnect();
        }
    }

    /**
     * 核心流量处理：从 TUN 接口读取 IP 包，转发到本地代理
     * 同时保护代理连接不被 VPN 循环拦截
     */
    private void processTraffic() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer packet = ByteBuffer.allocate(MTU);

        try {
            // 连接到本地代理（保护此连接不走 VPN 隧道，避免循环）
            DatagramChannel tunnel = DatagramChannel.open();
            tunnel.connect(new InetSocketAddress("127.0.0.1", 8080));
            protect(tunnel.socket()); // 关键：保护代理连接不被 VPN 拦截
            tunnel.configureBlocking(false);

            while (isRunning) {
                // 从 TUN 读取数据包
                int length = in.read(packet.array());
                if (length > 0) {
                    packet.limit(length);

                    try {
                        // 解析 IP 包头
                        byte[] data = new byte[length];
                        System.arraycopy(packet.array(), 0, data, 0, length);

                        int version = (data[0] >> 4) & 0xF;
                        if (version == 4) {
                            handleIPv4Packet(data, length, out, tunnel);
                        }
                        // IPv6 暂不处理，直接丢弃或转发

                    } catch (Exception e) {
                        Log.w(TAG, "处理数据包异常: " + e.getMessage());
                    }

                    packet.clear();
                } else {
                    // 没有数据时短暂休眠，避免 CPU 空转
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "流量处理线程异常: " + e.getMessage(), e);
        }
    }

    /**
     * 处理 IPv4 数据包
     * 提取目标地址和端口，转发给本地代理处理
     */
    private void handleIPv4Packet(byte[] data, int length, FileOutputStream out,
                                   DatagramChannel tunnel) throws Exception {
        // IP 包头解析
        int headerLen = (data[0] & 0xF) * 4;
        int protocol = data[9] & 0xFF;

        // 目标 IP
        String destIP = (data[16] & 0xFF) + "." + (data[17] & 0xFF) + "." +
                        (data[18] & 0xFF) + "." + (data[19] & 0xFF);

        // 忽略本地回环和 VPN 自身地址
        if (destIP.startsWith("127.") || destIP.startsWith("10.0.0.")) {
            return;
        }

        // TCP 协议 (6)：提取端口，转发给代理
        if (protocol == 6 && length > headerLen + 4) {
            int destPort = ((data[headerLen + 2] & 0xFF) << 8) | (data[headerLen + 3] & 0xFF);

            // HTTP(80) 和 HTTPS(443) 流量走代理劫持
            if (destPort == 80 || destPort == 443) {
                // 将原始数据包转发给本地代理
                ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
                tunnel.write(buf);
                return;
            }
        }

        // UDP 协议 (17)：DNS 查询直接转发
        if (protocol == 17 && length > headerLen + 2) {
            int destPort = ((data[headerLen + 2] & 0xFF) << 8) | (data[headerLen + 3] & 0xFF);
            if (destPort == 53) {
                // DNS 可以直接转发或本地处理
                return;
            }
        }
    }

    private void disconnect() {
        isRunning = false;

        if (proxyServer != null) {
            proxyServer.stop();
            proxyServer = null;
        }

        if (selector != null) {
            try { selector.close(); } catch (Exception ignored) {}
            selector = null;
        }

        if (vpnThread != null) {
            vpnThread.interrupt();
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

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        disconnect();
        super.onRevoke();
    }
}
