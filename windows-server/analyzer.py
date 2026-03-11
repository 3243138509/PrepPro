from openai import BadRequestError, OpenAI
from dataclasses import dataclass

import config


@dataclass
class AnalyzePayload:
    text: str
    model_notice: str = ""


def _normalize_model_error(exc: Exception) -> RuntimeError:
    msg = str(exc)
    lower = msg.lower()
    if "unauthorized" in lower or "authenticationerror" in lower:
        return RuntimeError(
            "模型服务鉴权失败：请检查 modelApiUrl 与 modelApiKey 是否匹配、密钥是否过期/已禁用。"
        )
    return RuntimeError(f"模型请求失败: {msg}")


def _build_client(api_url: str, api_key: str) -> OpenAI:
    return OpenAI(
        api_key=api_key,
        base_url=api_url,
        timeout=config.MODEL_TIMEOUT_SECONDS,
    )


def detect_model_names(api_url: str, api_key: str) -> list[str]:
    if not api_url.strip():
        raise RuntimeError("MODEL_API_URL is empty")
    if not api_key.strip():
        raise RuntimeError("MODEL_API_KEY is empty")

    client = _build_client(api_url.strip(), api_key.strip())
    try:
        models = client.models.list()
    except Exception as exc:
        normalized = _normalize_model_error(exc)
        raise RuntimeError(f"模型检测失败: {normalized}") from exc

    names: list[str] = []
    for item in getattr(models, "data", []) or []:
        model_id = getattr(item, "id", "")
        if model_id:
            names.append(str(model_id))
    if not names:
        raise RuntimeError("未检测到可用模型")
    return names


def _resolve_supported_model(api_url: str, api_key: str, current_model: str, err_msg: str) -> tuple[str, str]:
    lower_msg = err_msg.lower()
    if "not supported model" not in lower_msg:
        raise RuntimeError(f"模型请求失败: {err_msg}")

    models = detect_model_names(api_url, api_key)
    if not models:
        raise RuntimeError(f"当前模型不受支持: {current_model}，且未检测到可用模型")

    fallback = models[0]
    notice = ""
    if fallback != current_model:
        config.update_active_model_name(fallback)
        notice = f"已自动切换模型：{fallback}"
    return fallback, notice


def analyze_text_content(prompt: str, text_content: str) -> AnalyzePayload:
    profile = config.get_active_model_profile()
    api_key = profile.get("apiKey", "")
    api_url = profile.get("apiUrl", "")
    model_name = profile.get("modelName", "")

    if not api_key:
        raise RuntimeError("MODEL_API_KEY is empty; set DEEPSEEK_API_KEY in .env")

    if not api_url:
        raise RuntimeError("MODEL_API_URL is empty")

    if not model_name:
        raise RuntimeError("MODEL_NAME is empty")

    client = _build_client(api_url, api_key)

    merged_prompt = (
        f"{prompt}\n\n"
        "你是一个做题高手，以下是一个面试题目\n"
        f"{text_content}\n\n"
        "请分析上述文本内容，请先给出答案是什么，然后再给出简要的解析"
    )
    model_notice = ""

    try:
        response = client.chat.completions.create(
            model=model_name,
            messages=[
                {
                    "role": "user",
                    "content": merged_prompt,
                }
            ],
            temperature=0.2,
            stream=False,
        )
    except BadRequestError as exc:
        msg = str(exc)
        try:
            fallback_model, model_notice = _resolve_supported_model(api_url, api_key, model_name, msg)
            response = client.chat.completions.create(
                model=fallback_model,
                messages=[
                    {
                        "role": "user",
                        "content": merged_prompt,
                    }
                ],
                temperature=0.2,
                stream=False,
            )
        except Exception as fallback_exc:
            raise _normalize_model_error(fallback_exc) from exc

    content = (response.choices[0].message.content or "").strip()
    if not content:
        raise RuntimeError("model response content is empty")
    return AnalyzePayload(text=content, model_notice=model_notice)

def analyze_image_base64(image_base64: str, prompt: str) -> AnalyzePayload:
    profile = config.get_active_model_profile()
    api_key = profile.get("apiKey", "")
    api_url = profile.get("apiUrl", "")
    model_name = profile.get("modelName", "")

    if not api_key:
        raise RuntimeError("MODEL_API_KEY is empty; set DEEPSEEK_API_KEY in .env")

    if not api_url:
        raise RuntimeError("MODEL_API_URL is empty")

    if not model_name:
        raise RuntimeError("MODEL_NAME is empty")

    client = _build_client(api_url, api_key)

    model_notice = ""
    try:
        response = client.chat.completions.create(
            model=model_name,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{image_base64}",
                            },
                        },
                    ],
                }
            ],
            temperature=0.2,
            stream=False,
        )
    except BadRequestError as exc:
        msg = str(exc)
        recovered = False
        try:
            fallback_model, model_notice = _resolve_supported_model(api_url, api_key, model_name, msg)
            response = client.chat.completions.create(
                model=fallback_model,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{image_base64}",
                                },
                            },
                        ],
                    }
                ],
                temperature=0.2,
                stream=False,
            )
            recovered = True
        except Exception:
            pass
        if recovered:
            content = (response.choices[0].message.content or "").strip()
            if not content:
                raise RuntimeError("model response content is empty")
            return AnalyzePayload(text=content, model_notice=model_notice)
        if "unknown variant `image_url`" in msg or "expected `text`" in msg:
            raise RuntimeError(
                "当前模型不支持图片输入。请更换支持视觉的模型/服务，或改为文本解析。"
            ) from exc
        raise _normalize_model_error(exc) from exc

    content = (response.choices[0].message.content or "").strip()
    if not content:
        raise RuntimeError("model response content is empty")
    return AnalyzePayload(text=content, model_notice=model_notice)
