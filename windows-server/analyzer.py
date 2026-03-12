from openai import BadRequestError, OpenAI
from dataclasses import dataclass

import config


DEFAULT_ANALYSIS_MODE = "qa"
DEFAULT_CODE_LANGUAGE = "Python"

_LANGUAGE_ALIASES = {
    "py": "Python",
    "python": "Python",
    "java": "Java",
    "javascript": "JavaScript",
    "js": "JavaScript",
    "typescript": "TypeScript",
    "ts": "TypeScript",
    "c": "C",
    "c++": "C++",
    "cpp": "C++",
    "c#": "C#",
    "csharp": "C#",
    "go": "Go",
    "golang": "Go",
    "rust": "Rust",
    "kotlin": "Kotlin",
    "swift": "Swift",
    "php": "PHP",
    "ruby": "Ruby",
}


@dataclass
class AnalyzePayload:
    text: str
    model_notice: str = ""


def normalize_analysis_mode(mode: str | None) -> str:
    raw = (mode or "").strip().lower()
    if raw in {"code", "coding", "programming"}:
        return "code"
    return DEFAULT_ANALYSIS_MODE


def normalize_target_language(language: str | None) -> str:
    raw = (language or "").strip()
    if not raw:
        return DEFAULT_CODE_LANGUAGE

    alias = _LANGUAGE_ALIASES.get(raw.lower())
    if alias:
        return alias
    return raw


def build_analysis_prompt(
    user_prompt: str,
    *,
    source: str,
    mode: str | None = None,
    target_language: str | None = None,
) -> str:
    normalized_mode = normalize_analysis_mode(mode)
    normalized_language = normalize_target_language(target_language)
    source_text = "图片" if source == "image" else "题目文本"

    if normalized_mode == "code":
        parts = [
            "你现在是一个资深编程题解答助手。",
            f"你会根据给出的{source_text}识别编程题要求，并直接产出可提交的 {normalized_language} 代码。",
            "请先自行提炼输入输出、约束、边界条件和算法思路，再给出最终代码。",
            "输出要求：",
            f"1. 使用 {normalized_language} 输出完整可运行代码；",
            "2. 优先保证正确性，其次兼顾时间复杂度和空间复杂度；",
            "3. 如题目信息不足，基于常见 OJ/面试题约定做最稳妥的假设，并在代码前用一句话说明关键假设；",
            "4. 除非必要，不要输出与题目无关的说明；",
            "5. 如果存在多种做法，只给出你认为最合适提交的一种。",
        ]
    else:
        parts = [
            "你现在是一个解题专家。",
            f"请根据提供的{source_text}提取关键信息，直接给出答案结论，再给出简要解题思路。",
            "如果题目存在选项、条件或隐含限制，请在结论前先识别这些关键信息。",
            "回答保持简洁、明确，优先输出最终答案。",
        ]

    extra_prompt = user_prompt.strip()
    if extra_prompt:
        parts.extend(["", "额外要求：", extra_prompt])
    return "\n".join(parts)


def build_text_analysis_prompt(
    user_prompt: str,
    text_content: str,
    *,
    mode: str | None = None,
    target_language: str | None = None,
) -> str:
    prompt = build_analysis_prompt(
        user_prompt,
        source="text",
        mode=mode,
        target_language=target_language,
    )
    return f"{prompt}\n\n以下是题目内容：\n{text_content}"


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


def analyze_text_content(
    prompt: str,
    text_content: str,
    mode: str | None = None,
    target_language: str | None = None,
) -> AnalyzePayload:
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

    merged_prompt = build_text_analysis_prompt(
        prompt,
        text_content,
        mode=mode,
        target_language=target_language,
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

def analyze_image_base64(
    image_base64: str,
    prompt: str,
    mode: str | None = None,
    target_language: str | None = None,
) -> AnalyzePayload:
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
                        {
                            "type": "text",
                            "text": build_analysis_prompt(
                                prompt,
                                source="image",
                                mode=mode,
                                target_language=target_language,
                            ),
                        },
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
                            {
                                "type": "text",
                                "text": build_analysis_prompt(
                                    prompt,
                                    source="image",
                                    mode=mode,
                                    target_language=target_language,
                                ),
                            },
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
