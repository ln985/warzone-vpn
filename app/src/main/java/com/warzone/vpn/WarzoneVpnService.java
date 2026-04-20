package com.warzone.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class WarzoneVpnService extends VpnService {

    private static final String TAG = "WarzoneVPN";
    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private volatile boolean isRunning = false;

    // VPN 服务器配置 —— 替换为你的实际服务器地址和密钥
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final int VPN_PREFIX_LENGTH = 32;
    private static final String ROUTE_ADDRESS = "0.0.0.0";
    private static final int ROUTE_PREFIX_LENGTH = 0;
    private static final String DNS_SERVER = "8.8.8.8";
    private static final int MTU = 1500;

    // 远程 VPN 服务器地址和端口（需配置）
    private static final String SERVER_ADDRESS = "0.0.0.0";
    private static final int SERVER_PORT = 8080;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "disconnect".equals(intent.getStringExtra("action"))) {
            disconnect();
            return START_NOT_STICKY;
        }

        String province = intent != null ? intent.getStringExtra("province") : "";
        String city = intent != null ? intent.getStringExtra("city") : "";
        String district = intent != null ? intent.getStringExtra("district") : "";
        String adcode = intent != null ? intent.getStringExtra("adcode") : "";

        Log.i(TAG, "启动VPN - 地区: " + province + " > " + city + " > " + district + " [" + adcode + "]");

        if (isRunning) {
            disconnect();
        }

        startVpn(province, city, district, adcode);
        return START_STICKY;
    }

    private void startVpn(String province, String city, String district, String adcode) {
        isRunning = true;

        try {
            Builder builder = new Builder()
                    .setSession(province + (city.isEmpty() ? "" : " - " + city))
                    .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                    .addRoute(ROUTE_ADDRESS, ROUTE_PREFIX_LENGTH)
                    .addDnsServer(DNS_SERVER)
                    .setMtu(MTU);

            vpnInterface = builder.establish();

            if (vpnInterface == null) {
                Log.e(TAG, "无法建立 VPN 接口");
                stopSelf();
                return;
            }

            Log.i(TAG, "VPN 接口已建立，开始处理数据包...");

            vpnThread = new Thread(() -> {
                try {
                    handleVpnTraffic(province, city, district, adcode);
                } catch (Exception e) {
                    Log.e(TAG, "VPN 线程异常: " + e.getMessage());
                }
            }, "WarzoneVpnThread");
            vpnThread.start();

        } catch (Exception e) {
            Log.e(TAG, "启动 VPN 失败: " + e.getMessage());
            disconnect();
        }
    }

    /**
     * 处理 VPN 流量
     * 这里是核心逻辑，可以对接实际的 VPN 隧道协议
     */
    private void handleVpnTraffic(String province, String city, String district, String adcode) throws Exception {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer packet = ByteBuffer.allocate(MTU);

        // 示例：连接到远程 VPN 服务器
        // 实际使用时需替换 SERVER_ADDRESS 和 SERVER_PORT
        // DatagramChannel tunnel = DatagramChannel.open();
        // tunnel.connect(new InetSocketAddress(SERVER_ADDRESS, SERVER_PORT));
        // protect(tunnel.socket());  // 防止 VPN 循环

        Log.i(TAG, String.format("地区选择: %s/%s/%s [%s]", province, city, district, adcode));

        while (isRunning) {
            // 从 TUN 接口读取数据包
            int length = in.read(packet.array());
            if (length > 0) {
                packet.limit(length);

                // TODO: 在这里处理数据包
                // 可以解析 IP 包头、修改源地址、通过隧道转发等
                // 示例：直接回写（loopback，仅作演示）
                // out.write(packet.array(), 0, length);

                packet.clear();
            } else {
                Thread.sleep(100);
            }
        }
    }

    private void disconnect() {
        isRunning = false;

        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭 VPN 接口失败: " + e.getMessage());
            }
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
