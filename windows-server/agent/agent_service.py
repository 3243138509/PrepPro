import re

from langchain_core.language_models.chat_models import BaseChatModel

from .code_runner import run_python_code
from .model_provider import build_chat_model
from .schemas import AgentAnalyzeOutput, PythonExecutionReport

ROUTE_QA = "qa"
ROUTE_CODE = "code"
DEFAULT_CODE_LANGUAGE = "Python"


def _ensure_text(content: object) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                text = str(item.get("text", "")).strip()
                if text:
                    parts.append(text)
            else:
                raw = str(item).strip()
                if raw:
                    parts.append(raw)
        return "\n".join(parts).strip()
    return str(content).strip()


def _invoke_text(llm: BaseChatModel, prompt: str) -> str:
    message = llm.invoke(prompt)
    text = _ensure_text(getattr(message, "content", ""))
    if not text:
        raise RuntimeError("agent model response content is empty")
    return text


def _normalize_route(text: str) -> str:
    lower = text.lower()
    if ROUTE_CODE in lower:
        return ROUTE_CODE
    return ROUTE_QA


def _extract_code_block(text: str) -> str:
    matches = re.findall(r"```(?:[a-zA-Z0-9_+-]+)?\s*\n(.*?)```", text, flags=re.S)
    if matches:
        return matches[-1].strip()
    return text.strip()


def _wrap_code(language: str, code: str) -> str:
    lang = language.strip() or DEFAULT_CODE_LANGUAGE
    return f"```{lang.lower()}\n{code.strip()}\n```"


def _parse_improvement_options(raw: str) -> list[str]:
    options: list[str] = []
    for line in raw.splitlines():
        cleaned = re.sub(r"^\s*(?:[-*]|\d+[.)])\s*", "", line).strip()
        if cleaned:
            options.append(cleaned)
    deduped: list[str] = []
    for item in options:
        if item not in deduped:
            deduped.append(item)
    return deduped[:6]


def _classify_route(llm: BaseChatModel, source_text: str) -> str:
    prompt = (
        "你是题目分类器。请判断下列题目更适合回答问答还是写代码。\n"
        "只允许输出 qa 或 code，不能输出其他内容。\n\n"
        f"题目内容：\n{source_text}"
    )
    return _normalize_route(_invoke_text(llm, prompt))


def _generate_qa(llm: BaseChatModel, source_text: str) -> str:
    prompt = (
        "你是解题助手。请先给最终答案，再给简要思路，保持简洁。\n"
        "如果题目条件不足，给出最稳妥假设并说明。\n\n"
        f"题目内容：\n{source_text}"
    )
    return _invoke_text(llm, prompt)


def _generate_code(llm: BaseChatModel, source_text: str, target_language: str) -> str:
    language = target_language.strip() or DEFAULT_CODE_LANGUAGE
    prompt = (
        "你是资深编程题助手。请根据题目输出可提交代码。\n"
        f"要求：1) 使用 {language}；2) 只输出一个 Markdown 代码块；"
        "3) 优先正确性并考虑复杂度；4) 信息不足时做稳妥假设并在代码前一句话说明。\n\n"
        f"题目内容：\n{source_text}"
    )
    return _invoke_text(llm, prompt)


def _repair_python_code(
    llm: BaseChatModel,
    source_text: str,
    failed_code: str,
    report: PythonExecutionReport,
) -> str:
    prompt = (
        "你是 Python 修复助手。下面代码运行失败，请修复后返回可执行代码。\n"
        "只输出一个 Python Markdown 代码块，不要附加解释。\n\n"
        f"题目内容：\n{source_text}\n\n"
        f"失败代码：\n```python\n{failed_code}\n```\n\n"
        f"运行摘要：{report.summary}\n"
        f"stderr:\n{report.stderr or '(empty)'}\n"
    )
    return _invoke_text(llm, prompt)


def _generate_improvements(llm: BaseChatModel, source_text: str, route: str, answer_text: str) -> list[str]:
    prompt = (
        "基于题目与当前答案，生成 3~6 条可选改进项，供用户二次优化。\n"
        "每行一条，简短可执行，不要额外说明。\n"
        f"当前路线：{route}\n\n"
        f"题目内容：\n{source_text}\n\n"
        f"当前答案：\n{answer_text}"
    )
    raw = _invoke_text(llm, prompt)
    parsed = _parse_improvement_options(raw)
    if parsed:
        return parsed
    return ["优化边界条件处理", "优化可读性", "优化性能"]


def _apply_improvement(
    llm: BaseChatModel,
    source_text: str,
    route: str,
    current_text: str,
    improvement: str,
    target_language: str,
) -> str:
    language_hint = target_language.strip() or DEFAULT_CODE_LANGUAGE
    prompt = (
        "你是优化助手。请按用户选择对当前答案做定向优化。\n"
        f"用户选择：{improvement}\n"
        f"当前路线：{route}\n"
        f"代码语言偏好：{language_hint}\n\n"
        f"题目内容：\n{source_text}\n\n"
        f"当前答案：\n{current_text}\n\n"
        "输出要求：\n"
        "- 如果是代码路线，输出优化后的完整代码（Markdown 代码块）。\n"
        "- 如果是问答路线，输出优化后的答案和简要思路。\n"
        "- 不要输出与优化无关内容。"
    )
    return _invoke_text(llm, prompt)


def analyze_agent_text(
    source_text: str,
    *,
    target_language: str | None = None,
    improvement_request: str | None = None,
    current_text: str | None = None,
    route_hint: str | None = None,
    llm: BaseChatModel | None = None,
) -> AgentAnalyzeOutput:
    text = source_text.strip()
    if not text:
        raise RuntimeError("source text is empty")

    model = llm or build_chat_model()
    normalized_hint = (route_hint or "").strip().lower()
    if normalized_hint in {ROUTE_QA, ROUTE_CODE}:
        route = normalized_hint
    else:
        route = _classify_route(model, text)
    language = (target_language or DEFAULT_CODE_LANGUAGE).strip() or DEFAULT_CODE_LANGUAGE

    if current_text and improvement_request:
        base_answer = current_text.strip()
    elif route == ROUTE_CODE:
        base_answer = _generate_code(model, text, language)
    else:
        base_answer = _generate_qa(model, text)

    execution_report: PythonExecutionReport | None = None
    final_answer = base_answer

    if route == ROUTE_CODE and language.lower() == "python":
        code = _extract_code_block(base_answer)
        first_report = run_python_code(code)
        execution_report = first_report
        if not first_report.success:
            repaired_answer = _repair_python_code(model, text, code, first_report)
            repaired_code = _extract_code_block(repaired_answer)
            second_report = run_python_code(repaired_code)
            execution_report = second_report
            final_answer = _wrap_code("python", repaired_code)
        else:
            final_answer = _wrap_code("python", code)

    if improvement_request and improvement_request.strip():
        optimized = _apply_improvement(
            model,
            source_text=text,
            route=route,
            current_text=final_answer,
            improvement=improvement_request.strip(),
            target_language=language,
        )
        final_answer = optimized

    options = _generate_improvements(model, text, route, final_answer)
    return AgentAnalyzeOutput(
        text=final_answer,
        route=route,
        improvement_options=options,
        execution_report=execution_report,
    )

