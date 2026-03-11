# TCP 协议（当前实现）

## 1. 传输帧格式

## 协议变更日志

- 协议变更对照（v1.0 -> 当前）：`docs/protocol-changelog.md`

使用长度前缀帧：

- 前 4 字节：无符号大端整数 `length`
- 后续 `length` 字节：UTF-8 JSON

示意：

```text
| 4 bytes length (big-endian) | UTF-8 JSON payload |
```

服务端限制：

- 单帧最大长度 `RC_MAX_FRAME_SIZE`（默认 12 MiB）
- `length <= 0` 或超过上限会直接报错并断开本次处理

## 2. 连接与鉴权

连接建立后，服务端要求第一条业务消息必须是 `AUTH`。

### 客户端 -> 服务端

```json
{
  "type": "AUTH",
  "password": "changeme"
}
```

### 服务端 -> 客户端

```json
{
  "type": "AUTH_OK"
}
```

注意：当前实现只要求先发送 `AUTH`，并不会校验 `password` 是否正确。

如果未先鉴权就发送其他消息，返回：

```json
{
  "type": "ERROR",
  "code": "ERROR_AUTH_REQUIRED",
  "message": "auth required"
}
```

## 3. 通用错误格式

```json
{
  "type": "ERROR",
  "requestId": "uuid-string (可选)",
  "code": "ERROR_XXX",
  "message": "错误描述"
}
```

## 4. 截图相关

### 4.1 请求截图

客户端发送：

```json
{
  "type": "CAPTURE",
  "requestId": "uuid-string",
  "quality": 75,
  "displayId": 1
}
```

字段说明：

- `quality` 范围会被服务端钳制到 30~95
- `displayId` 默认 1

成功返回：

```json
{
  "type": "IMAGE",
  "requestId": "uuid-string",
  "displayId": 1,
  "format": "jpeg",
  "width": 1920,
  "height": 1080,
  "imageBase64": "..."
}
```

失败返回：

```json
{
  "type": "ERROR",
  "requestId": "uuid-string",
  "code": "ERROR_CAPTURE",
  "message": "capture failed (displayId=1)"
}
```

### 4.2 获取显示器列表

客户端发送：

```json
{
  "type": "LIST_DISPLAYS"
}
```

成功返回：

```json
{
  "type": "DISPLAYS",
  "displays": [
    {
      "id": 1,
      "left": 0,
      "top": 0,
      "width": 1920,
      "height": 1080
    }
  ]
}
```

失败返回：

```json
{
  "type": "ERROR",
  "code": "ERROR_DISPLAYS",
  "message": "list displays failed"
}
```

## 5. 解析相关

### 5.1 图片解析

客户端发送：

```json
{
  "type": "ANALYZE_IMAGE",
  "requestId": "uuid-string",
  "imageBase64": "...",
  "prompt": "可选，自定义提示词"
}
```

成功返回：

```json
{
  "type": "ANALYZE_RESULT",
  "requestId": "uuid-string",
  "text": "...模型输出...",
  "ocrText": "...OCR 文本（可能为空）...",
  "modelNotice": "...模型提示（可能为空）..."
}
```

可能错误码：

- `ERROR_ANALYZE_INPUT`：`imageBase64` 为空
- `ERROR_OCR`：OCR 过程失败
- `ERROR_OCR_EMPTY`：要求 OCR 非空但结果为空
- `ERROR_ANALYZE`：模型分析失败

### 5.2 文本解析

客户端发送：

```json
{
  "type": "ANALYZE_TEXT",
  "requestId": "uuid-string",
  "text": "需要解析的文本",
  "prompt": "可选，自定义提示词"
}
```

成功返回：

```json
{
  "type": "ANALYZE_RESULT",
  "requestId": "uuid-string",
  "text": "...模型输出...",
  "ocrText": "",
  "modelNotice": "...模型提示（可能为空）..."
}
```

可能错误码：

- `ERROR_ANALYZE_TEXT_INPUT`
- `ERROR_ANALYZE`

## 6. 剪贴板相关

### 6.1 订阅剪贴板推送

客户端发送：

```json
{
  "type": "SUBSCRIBE_CLIPBOARD"
}
```

服务端检测到电脑端剪贴板变化后推送：

```json
{
  "type": "CLIPBOARD_TEXT",
  "requestId": "uuid-string",
  "text": "剪贴板文本（按上限截断）"
}
```

注意：当前实现中，进入 `SUBSCRIBE_CLIPBOARD` 后该连接会持续轮询并推送，不再处理同连接上的其他请求。建议为订阅使用独立连接。

### 6.2 设置电脑端剪贴板

客户端发送：

```json
{
  "type": "SET_CLIPBOARD_TEXT",
  "requestId": "uuid-string",
  "text": "要写入的文本"
}
```

成功返回：

```json
{
  "type": "CLIPBOARD_SET_OK",
  "requestId": "uuid-string"
}
```

可能错误码：

- `ERROR_CLIPBOARD_INPUT`
- `ERROR_CLIPBOARD_SET`

## 7. 模型配置相关

### 7.1 获取模型配置

客户端发送：

```json
{
  "type": "GET_MODEL_SETTINGS"
}
```

服务端返回：

```json
{
  "type": "MODEL_SETTINGS",
  "profiles": [
    {
      "apiUrl": "https://api.deepseek.com",
      "apiKey": "sk-...",
      "modelName": "deepseek-chat"
    }
  ],
  "activeIndex": 0
}
```

### 7.2 检测可用模型名

客户端发送：

```json
{
  "type": "DETECT_MODELS",
  "requestId": "uuid-string",
  "modelApiUrl": "https://api.deepseek.com",
  "modelApiKey": "sk-..."
}
```

服务端返回：

```json
{
  "type": "MODEL_NAMES",
  "requestId": "uuid-string",
  "models": ["deepseek-chat", "deepseek-reasoner"]
}
```

失败错误码：`ERROR_DETECT_MODELS`

### 7.3 新增模型配置

客户端发送：

```json
{
  "type": "ADD_MODEL_SETTING",
  "requestId": "uuid-string",
  "modelApiUrl": "https://api.deepseek.com",
  "modelApiKey": "sk-...",
  "modelName": "deepseek-chat",
  "setActive": true
}
```

服务端返回 `MODEL_SETTINGS`。

失败错误码：`ERROR_ADD_MODEL_SETTING`

### 7.4 切换当前模型

客户端发送：

```json
{
  "type": "SET_ACTIVE_MODEL",
  "requestId": "uuid-string",
  "index": 1
}
```

服务端返回 `MODEL_SETTINGS`。

失败错误码：`ERROR_SET_ACTIVE_MODEL`

### 7.5 删除模型配置

客户端发送：

```json
{
  "type": "DELETE_MODEL_SETTING",
  "requestId": "uuid-string",
  "index": 1
}
```

服务端返回 `MODEL_SETTINGS`。

失败错误码：`ERROR_DELETE_MODEL_SETTING`

## 8. 未知消息类型

当 `type` 未被支持时返回：

```json
{
  "type": "ERROR",
  "code": "ERROR_UNKNOWN_TYPE",
  "message": "unknown message type: XXX"
}
```

## 9. 客户端实现建议（基于当前服务端）

- 每个连接先发 `AUTH`，收到 `AUTH_OK` 后再发其他请求。
- `SUBSCRIBE_CLIPBOARD` 使用独立连接。
- `CAPTURE`、`ANALYZE_IMAGE` 建议携带 `requestId` 以便对应响应。
- 单条消息体尽量控制在 12 MiB 内（或与服务端 `RC_MAX_FRAME_SIZE` 保持一致）。
