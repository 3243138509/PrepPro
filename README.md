<div align="center">
  <img width="42%" src="docs/image_video/icon.png" alt="PrepPro">

# PrepPro MVP

手机端与电脑服务端互联
实时预览、剪切板监听、问题解析、结果回传。

<p>
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Windows-3a86ff" alt="Platform">
  <img src="https://img.shields.io/badge/Python-3.10+-3776AB?logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/Protocol-TCP-00b894" alt="Protocol">
  <img src="https://img.shields.io/badge/OCR-RapidOCR-orange" alt="OCR">
</p>

</div>

---

## 目录

- [简介](#-简介)
- [快速入门](#-快速入门)
- [iOS 客户端](#-ios-客户端)
- [Windows 安装包](#-windows-安装包)
- [效果阐述](#-效果阐述)
- [注意事项](#️-注意事项)
- [声明](#-声明)

---

## ✨ 简介

# PrepPro MVP

PrepPro MVP 是一款专为正在应对线上笔试的同学精心打造的轻量级 AI 辅助工具。系统运行成本极低，全程仅涉及 Token 的消耗，无需任何额外开支——完成一次线上笔试，成本仅需几分钱。

使用时，手机通过 TCP 协议连接至电脑服务端，即可一键触发屏幕截图，并将画面实时回传至手机显示。与此同时，系统内置了 OCR 预扫描与模型解析能力，能够智能辅助处理题目内容，帮助同学更从容地应对答题过程。

本系统的设计初衷，是希望为预算有限的同学提供一个真正可负担的 AI 辅助方案。相比市面上一些商业化线上笔试辅助工具往往成本较高，PrepPro MVP 以极低的门槛，让技术真正服务于每一位需要帮助的同学。



### 主要能力

- 📸 远程截图、实时预览、裁剪保存
- 🧠 图片上传解析（OCR 预扫描 + 文本模型分析）
- ⚙️ 多模型配置与切换（支持市面上大部分语言模型）
- 📋 电脑剪贴板文本推送到手机端，支持按需解析
---

## 🚀 快速入门

### 1) 服务端启动（三选一）
（Windows安装包，推荐）



<p align="center">
  <img src="docs\image_video\setup.png" alt="windows" width="100%">
  <font color="black" size="4">注意：</font>
  <font color="red" size="4">安装时路径中不能出现中文</font>
</p>

（Windows）

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

服务端启动（通用方式）
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




### 2) 服务端页面展示

<p align="center">
  <img src="docs\image_video\windows.png" alt="windows" width="100%">
</p>

这个地址，需要记一下复制给Android客户端


### 3) Android 客户端连接

1.  打开 `android-app`
2. 手机与电脑接入同一局域网
3. 在 App 中填写电脑 IP、端口（即第二步所示）
4. 点击连接后执行截图与预览

<p align="center">
  <img src="docs\image_video\3.jpg" width="70%">
</p>

5. 模型选择

<p align="center">
  <img src="docs\image_video\4.png" alt="操作1" width="120"/>
  <img src="docs\image_video\5.png" alt="操作2" width="120"/>
  <img src="docs\image_video\6.png" alt="操作3" width="120"/>
</p>

## 🍎 iOS 客户端

已新增 iOS MVP 骨架目录：

- `ios-app/`

当前支持：

- TCP 连接 Windows 服务端
- AUTH 鉴权
- CAPTURE 截图请求与图片展示

使用说明见：

- `ios-app/README.md`

## 🎬 效果阐述
### 1) 实时预览

<video width="640" height="360" controls>
  <source src="docs\image_video\7.mp4" type="video/mp4">
</video>

### 2) 剪切板监测
在电脑端复制一段内容，手机端会出现弹框
<p align="center">
  <img src="docs\image_video\8.jpg" width="70%">
</p>

### 3) 解析效果
<p align="center">
  <img src="docs\image_video\9.jpg" width="70%">
</p>

---

## ⚠️ 注意事项

> 当前版本为 1.0.1，适合局域网场景快速验证。

- 仅支持局域网 + 口令鉴权，不包含 TLS 加密
- 截图默认采用 JPEG 压缩，优先降低传输时延
- Ubuntu 桌面优先使用 `mss` 截图，不可用时自动回退到 `gnome-screenshot`、`scrot` 或 `import`

## 📦 Windows 安装包

电脑端支持生成 install/uninstall 的 EXE 流程，详见文档：

- [docs/windows-installer.md](docs/windows-installer.md)

快速构建命令：

```powershell
cd windows-server
./build-installer.ps1
```

构建成功后产物在：

- `windows-server/dist-installer/PrepPro-Setup.exe`

安装后会自动生成卸载程序：

- `%LOCALAPPDATA%\Programs\PrepPro\unins000.exe`

## 📌 声明

本项目仅作为学习项目，用于学习应用开发，不允许私自用于不法用途，希望大家诚信考试！！！


## 🙏 感谢

本项目的OCR为 https://github.com/hiroi-sora/RapidOCR-json 的离线OCR引擎

## 🛠️ 规划

- 增加代码模式，对编程题的不同语言进行适配
- 优化UI布局，使app端更加美观
- 增加视觉检测，对题目进行标注
- 制做一个工作流用于自动化做题（例如部分测评）


