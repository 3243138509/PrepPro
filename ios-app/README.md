# PrepPro iOS App (MVP)

该目录是 PrepPro iOS 客户端的 MVP 骨架，基于 SwiftUI + Network.framework。

## 当前能力

- 连接 Windows 服务端（TCP）
- 发送 AUTH 鉴权
- 发送 CAPTURE 截图请求
- 接收 IMAGE 并在页面显示
- 显示错误与连接状态

## 在 macOS 上生成并运行

1. 安装 Xcode（建议 15+）
2. 可选：安装 XcodeGen
3. 在本目录执行：

```bash
xcodegen generate
open PrepProIOS.xcodeproj
```

如果你不使用 XcodeGen，也可以手动新建同名 SwiftUI 工程并拷贝 `PrepProIOS/` 下源码文件。

## 权限说明

工程已配置本地网络访问描述（Local Network Usage）。
首次连接局域网服务端时，iOS 会弹出网络权限提示。
