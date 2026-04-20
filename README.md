# 战区 VPN

省市区选择 + VPN 连接的 Android 应用。

## 功能

- **三级联动选择**：省 → 市 → 区，无区自动跳过
- **VPN 连接**：通过 Android VpnService 建立 VPN 隧道
- **地区代码**：使用行政区划代码 (adcode) 标识选中地区

## 使用方式

1. 用 Android Studio 打开项目
2. 在 `WarzoneVpnService.java` 中配置你的 VPN 服务器地址和端口
3. 构建并安装到设备
4. 选择地区 → 点击"开始修改"

## 项目结构

```
app/src/main/
├── AndroidManifest.xml          # 权限配置 (VPN/网络)
├── assets/warzone.json          # 省市区数据 (34省, 473市)
├── java/com/warzone/vpn/
│   ├── MainActivity.java        # 主界面 - 三级联动 Spinner
│   └── WarzoneVpnService.java   # VPN 服务 - VpnService 实现
└── res/layout/
    └── activity_main.xml        # 主界面布局
```

## 待配置

在 `WarzoneVpnService.java` 中修改以下常量：

```java
private static final String SERVER_ADDRESS = "你的VPN服务器IP";
private static final int SERVER_PORT = 8080;
private static final String VPN_ADDRESS = "10.0.0.2";
```
