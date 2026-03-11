# 手动联调步骤

1. 服务端（Windows 或 Ubuntu）运行 `python main.py`
2. 确认系统防火墙放行 TCP 5001
3. 手机与服务端连接同一局域网
4. 手机 App 输入：
   - Host: 服务端局域网 IP（如 `192.168.1.10`）
   - Port: `5001`
   - Password: `changeme`
5. 点击“连接并截图”
6. 预期：2~3 秒内显示截图

## 异常检查

- 连接失败：检查 IP、端口、防火墙
- 鉴权失败：检查口令一致
- 截图失败（Windows）：检查当前会话是否可见桌面
- 截图失败（Ubuntu）：检查是否在图形桌面会话，并安装 `gnome-screenshot` 或 `scrot`（`import` 也可）
