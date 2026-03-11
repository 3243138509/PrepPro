# 协议变更日志（v1.0 -> 当前）

本文档用于帮助 Android / iOS 客户端快速识别协议差异并完成兼容升级。

## 版本范围

- v1.0：早期 MVP 文档阶段（以截图与基础解析为主）
- 当前：以 windows-server 当前实现为准（见 docs/protocol.md）

## 总览

### 新增能力

- 文本解析：ANALYZE_TEXT
- 剪贴板写入：SET_CLIPBOARD_TEXT -> CLIPBOARD_SET_OK
- 模型配置管理：GET_MODEL_SETTINGS / ADD_MODEL_SETTING / SET_ACTIVE_MODEL / DELETE_MODEL_SETTING
- 模型检测：DETECT_MODELS -> MODEL_NAMES

### 语义调整

- AUTH：当前实现仅要求先发送 AUTH，不校验 password 值
- SUBSCRIBE_CLIPBOARD：进入后该连接持续推送，不再处理同连接其他请求

### 错误码扩展

新增或明确以下错误码：

- ERROR_AUTH_REQUIRED
- ERROR_ANALYZE_TEXT_INPUT
- ERROR_CLIPBOARD_INPUT
- ERROR_CLIPBOARD_SET
- ERROR_DETECT_MODELS
- ERROR_ADD_MODEL_SETTING
- ERROR_SET_ACTIVE_MODEL
- ERROR_DELETE_MODEL_SETTING
- ERROR_UNKNOWN_TYPE

## 详细差异

## 1) 鉴权

### v1.0 预期

- AUTH 可能被理解为需要严格 password 校验。

### 当前实现

- 只要求连接后先发送 AUTH。
- 若未先鉴权即发送其他消息，返回 ERROR_AUTH_REQUIRED。
- password 字段目前不参与校验逻辑。

### 客户端建议

- 保留 password 字段，继续发送 AUTH，避免未来服务端恢复校验时不兼容。

## 2) 解析能力

### v1.0

- 重点是 ANALYZE_IMAGE。

### 当前

- 新增 ANALYZE_TEXT。
- ANALYZE_IMAGE 在 OCR 失败/为空时会出现 ERROR_OCR 或 ERROR_OCR_EMPTY（取决于配置）。
- ANALYZE_RESULT 增加 modelNotice 字段（可为空）。

### 客户端建议

- 解析结果结构按 text + ocrText + modelNotice 读取，modelNotice 允许为空。

## 3) 剪贴板能力

### v1.0

- 主要是订阅推送 CLIPBOARD_TEXT。

### 当前

- 新增 SET_CLIPBOARD_TEXT 主动写入。
- 成功响应 CLIPBOARD_SET_OK。
- 订阅连接是长轮询推送通道。

### 客户端建议

- 使用双连接模型：
  - 连接 A：普通请求（截图/解析/模型配置）
  - 连接 B：SUBSCRIBE_CLIPBOARD 专用

## 4) 模型配置能力

### v1.0

- 多数客户端使用固定模型配置。

### 当前

- 已支持完整模型配置增删改（切换）与可用模型检测。
- MODEL_SETTINGS 统一返回 profiles + activeIndex。

### 客户端建议

- 将本地模型设置页改为服务端驱动（拉取 MODEL_SETTINGS 后渲染）。

## 5) 帧与体积限制

### v1.0

- 通常仅描述长度前缀帧。

### 当前

- 保持 4 字节大端长度 + UTF-8 JSON。
- 默认单帧上限 12 MiB（RC_MAX_FRAME_SIZE）。

### 客户端建议

- 发送前做体积检查，避免过大图片/文本导致服务端拒绝。

## Android 升级清单

1. 保留连接后首条 AUTH。
2. 若使用剪贴板订阅，拆分专用连接。
3. 增加对 CLIPBOARD_SET_OK、MODEL_SETTINGS、MODEL_NAMES 的解析。
4. 错误弹窗增加对新错误码映射。
5. 解析结果页兼容 modelNotice 展示。

## iOS 升级清单

1. 网络层支持所有新增 type。
2. ViewModel 引入模型配置状态（profiles + activeIndex）。
3. 剪贴板订阅改独立连接，避免阻塞主请求。
4. 统一错误码文案映射。
5. 对 ANALYZE_RESULT 的 modelNotice 做可选展示。

## 兼容策略建议

- 向后兼容：客户端遇到未知 type 时忽略并记录日志。
- 向前兼容：服务端新增字段时客户端不要做严格字段白名单反序列化。
- 灰度策略：优先上线“可识别新消息但不强依赖新消息”的客户端版本。

## 参考

- 当前协议主文档：docs/protocol.md
- 服务端实现：windows-server/main.py, windows-server/protocol.py
