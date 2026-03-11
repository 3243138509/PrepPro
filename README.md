<div align="center">
	<img width="42%" src="docs/image/icon.png" alt="Remote Capture">

# Remote Capture MVP

手机端连接电脑服务端，一键截图、实时预览、结果回传。

</div>

---

## 目录

- [简介](#简介)
- [快速入门](#快速入门)
- [注意事项](#注意事项)

---

## 简介

Remote Capture MVP 是一个手机端与电脑端协同的远程截图与解析系统。

手机通过 TCP 连接到电脑服务端后，可触发截图并回传到手机显示，同时支持 OCR 预扫描与模型解析流程。

### 主要能力

- 远程截图、实时预览、裁剪保存
- 图片上传解析（OCR 预扫描 + 文本模型分析）
- 多模型配置与切换（MODEL_API_URL / MODEL_API_KEY / MODEL_NAME）
- 电脑剪贴板文本推送到手机端，支持按需解析
---

## 快速入门

### 1) 服务端启动（Windows，推荐）

进入 `windows-server`，双击 `get-start.bat`。

脚本会自动完成：

1. 创建 `.venv`（首次）
2. 安装依赖（首次或依赖变化时）
3. 启动 `main.py`

也可手动执行：

```powershell
cd windows-server
./get-start.ps1
```

可选参数：

- `-SkipInstall`：跳过依赖安装
- `-SkipRun`：只准备环境，不启动服务

### 2) 服务端启动（Linux / 通用方式）

```bash
cd windows-server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

在 Windows PowerShell 中激活虚拟环境：

```powershell
.venv\Scripts\Activate.ps1
```

### 3) Android 客户端连接

1. 用 Android Studio 打开 `android-app`
2. 手机与电脑接入同一局域网
3. 在 App 中填写电脑 IP、端口、口令
4. 点击连接后执行截图与预览
5. 左滑到第二页查看解析结果

### 4) 默认连接参数

| 项目 | 默认值 |
| --- | --- |
| 监听地址 | `0.0.0.0:5001` |
| 默认口令 | `changeme` |

---

## 注意事项

> 当前版本为 MVP，适合局域网场景快速验证。

- 仅支持局域网 + 口令鉴权，不包含 TLS 加密
- OCR 预扫描默认开启，需先安装 Tesseract OCR
- 截图默认采用 JPEG 压缩，优先降低传输时延
- Ubuntu 桌面优先使用 `mss` 截图，不可用时自动回退到 `gnome-screenshot`、`scrot` 或 `import`

### 环境变量

| 变量名 | 说明 | 默认值 |
| --- | --- | --- |
| `RC_OCR_ENABLED` | 是否启用 OCR 预扫描 | `true` |
| `RC_OCR_REQUIRED` | OCR 为空时是否阻断上传 | `true` |
| `RC_OCR_LANG` | OCR 语言 | `chi_sim+eng` |
| `RC_OCR_TESSERACT_CMD` | Tesseract 可执行文件路径（未设置走 PATH） | - |
| `RC_CLIPBOARD_POLL_INTERVAL` | 剪贴板轮询间隔（秒） | `0.6` |
| `RC_CLIPBOARD_MAX_TEXT_CHARS` | 剪贴板推送最大长度 | `4000` |
