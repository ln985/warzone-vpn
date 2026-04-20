# 战区代理 VPN

本地 VPN 代理 + 流量劫持 Android 应用。

## 架构

```
手机所有流量
    ↓
VpnService TUN 接口 (拦截)
    ↓
本地代理服务器 127.0.0.1:8080
    ↓
注入地区请求头 (X-Adcode / X-Region / ...)
    ↓
转发到目标服务器
```

## 注入的请求头

| Header | 值 | 说明 |
|--------|-----|------|
| `X-Adcode` | `340803` | 行政区划代码 |
| `X-Region` | `安徽省 安庆市 大观区` | 完整地区名 |
| `X-Location-Code` | `340803` | 地区位置代码 |
| `X-Forwarded-Region` | `安徽省 安庆市 大观区` | 转发地区 |

## 项目结构

```
app/src/main/java/com/warzone/vpn/
├── MainActivity.java       # UI: 省市区三级联动 + 启动按钮
├── LocalVpnService.java    # VpnService: 拦截设备所有流量
├── LocalProxyServer.java   # 本地代理: 注入请求头 + 流量转发
```

## 使用方式

1. 安装 APK 到 Android 设备
2. 选择省市区（无区自动跳过）
3. 点击"开始修改" → 弹出 VPN 权限 → 确认
4. 所有 HTTP/HTTPS 流量将自动注入地区头

## 构建

```bash
# GitHub Actions 自动构建
git push origin main
# 从 Actions 页面下载 APK
```
