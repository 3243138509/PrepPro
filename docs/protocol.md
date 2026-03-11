# TCP 协议（MVP）

采用长度前缀帧：

- 每个帧以 4 字节无符号大端整数 `length` 开头。
- 后续 `length` 字节为 UTF-8 JSON 文本。
- 对于图片响应，JSON 中包含 Base64 图片内容（MVP 简化实现）。

## 通用字段

```json
{
  "type": "AUTH|CAPTURE|LIST_DISPLAYS|DISPLAYS|ANALYZE_IMAGE|ANALYZE_RESULT|IMAGE|ERROR",
  "requestId": "uuid-string"
}
```

## 鉴权

客户端发送：

```json
{
  "type": "AUTH",
  "password": "changeme"
}
```

服务端返回：

```json
{
  "type": "AUTH_OK"
}
```

失败：

```json
{
  "type": "ERROR",
  "code": "ERROR_AUTH",
  "message": "invalid password"
}
```

## 截图请求

客户端发送：

```json
{
  "type": "CAPTURE",
  "requestId": "uuid-string",
  "quality": 75,
  "displayId": 1
}
```

服务端成功返回：

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

## 显示器列表请求

客户端发送：

```json
{
  "type": "LIST_DISPLAYS"
}
```

## 上传图片并解析

客户端发送：

```json
{
  "type": "ANALYZE_IMAGE",
  "requestId": "uuid-string",
  "imageBase64": "...",
  "prompt": "请用简洁中文描述这张图里的主要内容，并列出关键元素。"
}
```

服务端返回：

```json
{
  "type": "ANALYZE_RESULT",
  "requestId": "uuid-string",
  "text": "...模型解析结果...",
  "ocrText": "...上传前 OCR 扫描提取文本（可为空）...",
  "modelNotice": "已自动切换模型：xxx（可为空）"
}
```

说明：

- 服务端在调用视觉模型前，会先执行一次 OCR 扫描（可通过服务端配置关闭）。
- 当 `RC_OCR_REQUIRED=true` 且 OCR 结果为空时，服务端会拒绝上传并返回错误。

OCR 相关错误：

```json
{
  "type": "ERROR",
  "requestId": "uuid-string",
  "code": "ERROR_OCR|ERROR_OCR_EMPTY",
  "message": "..."
}
```

服务端返回：

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
    },
    {
      "id": 2,
      "left": 1920,
      "top": 0,
      "width": 2560,
      "height": 1440
    }
  ]
}
```
```

失败返回：

```json
{
  "type": "ERROR",
  "requestId": "uuid-string",
  "code": "ERROR_CAPTURE",
  "message": "capture failed"
}
```

## 超时与重试建议

- 客户端读超时：8 秒。
- 连接失败可重试 1~2 次。
- 单连接串行请求（MVP），避免并发复杂度。

## 文本解析请求

客户端发送：

```json
{
  "type": "ANALYZE_TEXT",
  "requestId": "uuid-string",
  "text": "需要解析的文本",
  "prompt": "可选，自定义提示词"
}
```

服务端返回：

```json
{
  "type": "ANALYZE_RESULT",
  "requestId": "uuid-string",
  "text": "...模型解析结果...",
  "ocrText": ""
}
```

## 剪贴板订阅推送

客户端发送：

```json
{
  "type": "SUBSCRIBE_CLIPBOARD"
}
```

服务端在检测到电脑端剪贴板文本变化后，主动推送：

```json
{
  "type": "CLIPBOARD_TEXT",
  "requestId": "uuid-string",
  "text": "当前复制文本"
}
```

## 模型设置与检测

### 获取模型配置

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
  "activeIndex": 0,
  "profiles": [
    {
      "apiUrl": "https://api.deepseek.com",
      "apiKey": "sk-...",
      "modelName": "deepseek-chat"
    }
  ]
}
```

### 检测可用模型名

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

### 新增模型配置

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

服务端返回同 `MODEL_SETTINGS`。

### 切换当前模型

客户端发送：

```json
{
  "type": "SET_ACTIVE_MODEL",
  "requestId": "uuid-string",
  "index": 1
}
```

服务端返回同 `MODEL_SETTINGS`。

### 删除模型配置

客户端发送：

```json
{
  "type": "DELETE_MODEL_SETTING",
  "requestId": "uuid-string",
  "index": 1
}
```

服务端返回同 `MODEL_SETTINGS`。
