from langchain_core.language_models.chat_models import BaseChatModel

import config


def build_chat_model() -> BaseChatModel:
    profile = config.get_active_model_profile()
    api_key = str(profile.get("apiKey", "")).strip()
    api_url = str(profile.get("apiUrl", "")).strip()
    model_name = str(profile.get("modelName", "")).strip()

    if not api_key:
        raise RuntimeError("MODEL_API_KEY is empty; 请先在手机端配置模型 URL/KEY 并同步")
    if not api_url:
        raise RuntimeError("MODEL_API_URL is empty")
    if not model_name:
        raise RuntimeError("MODEL_NAME is empty")

    try:
        from langchain_openai import ChatOpenAI
    except Exception as exc:  # pragma: no cover
        raise RuntimeError(
            "langchain_openai 未安装，请在 windows-server/requirements.txt 中安装 langchain-openai。"
        ) from exc

    return ChatOpenAI(
        model=model_name,
        api_key=api_key,
        base_url=api_url,
        timeout=config.MODEL_TIMEOUT_SECONDS,
        temperature=0.2,
    )

