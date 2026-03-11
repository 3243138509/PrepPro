# Windows 安装与卸载 EXE

本文档说明如何为 PrepPro 电脑端构建安装程序（install exe），以及卸载程序（uninstall exe）的使用方式。

## 前置条件

1. Windows 10/11
2. 已安装 Inno Setup 6（含 ISCC.exe）

## 生成安装 EXE

在项目根目录执行：

```powershell
cd windows-server
./build-installer.ps1
```

生成结果：

- 安装程序：windows-server/dist-installer/PrepPro-Setup.exe

可选参数：

- -CleanOutput：清理旧的输出目录后再构建
- -InnoCompilerPath：手动指定 ISCC.exe 路径

示例：

```powershell
./build-installer.ps1 -CleanOutput -InnoCompilerPath "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
```

## 安装后如何卸载

安装完成后，系统会自动生成卸载 EXE：

- %LOCALAPPDATA%\Programs\PrepPro\unins000.exe

也可以通过以下方式卸载：

1. 开始菜单中的“卸载 PrepPro”
2. Windows 设置 -> 应用 -> 已安装的应用 -> PrepPro -> 卸载

## 卸载行为说明

卸载时会额外清理以下目录/文件：

- .venv
- log
- server.log

这样可以避免残留虚拟环境和日志占用磁盘空间。
