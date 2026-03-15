from dataclasses import dataclass

from agent import agent_service
from agent.schemas import PythonExecutionReport


@dataclass
class _Reply:
    content: str


class _FakeLLM:
    def __init__(self, replies: list[str]):
        self._replies = list(replies)

    def invoke(self, _prompt: str) -> _Reply:
        if not self._replies:
            raise AssertionError("no more fake replies configured")
        return _Reply(content=self._replies.pop(0))


def test_analyze_agent_text_routes_to_qa():
    llm = _FakeLLM(
        [
            "qa",
            "答案：42",
            "- 提高结论可读性\n- 补充关键条件",
        ]
    )
    result = agent_service.analyze_agent_text("1+1=?", llm=llm)
    assert result.route == "qa"
    assert "42" in result.text
    assert result.execution_report is None
    assert len(result.improvement_options) == 2


def test_analyze_agent_text_python_success(monkeypatch):
    monkeypatch.setattr(
        agent_service,
        "run_python_code",
        lambda _code: PythonExecutionReport(success=True, summary="ok", return_code=0),
    )
    llm = _FakeLLM(
        [
            "code",
            "```python\nprint('ok')\n```",
            "1. 提高可读性",
        ]
    )
    result = agent_service.analyze_agent_text("输出 ok", target_language="Python", llm=llm)
    assert result.route == "code"
    assert "print('ok')" in result.text
    assert result.execution_report is not None
    assert result.execution_report.success is True


def test_analyze_agent_text_python_auto_repair(monkeypatch):
    reports = [
        PythonExecutionReport(success=False, summary="bad", return_code=1, stderr="SyntaxError"),
        PythonExecutionReport(success=True, summary="ok", return_code=0),
    ]

    def _fake_run(_code: str) -> PythonExecutionReport:
        return reports.pop(0)

    monkeypatch.setattr(agent_service, "run_python_code", _fake_run)
    llm = _FakeLLM(
        [
            "code",
            "```python\nprint(\n```",
            "```python\nprint('fixed')\n```",
            "- 优化边界处理",
        ]
    )
    result = agent_service.analyze_agent_text("输出 fixed", target_language="Python", llm=llm)
    assert "print('fixed')" in result.text
    assert result.execution_report is not None
    assert result.execution_report.success is True


def test_analyze_agent_text_with_improvement_request_uses_current_text():
    llm = _FakeLLM(
        [
            "code",
            "```python\nprint('optimized')\n```",
            "- 继续优化性能",
        ]
    )
    result = agent_service.analyze_agent_text(
        "输出 optimized",
        target_language="Python",
        improvement_request="优化性能",
        current_text="```python\nprint('base')\n```",
        llm=llm,
    )
    assert "optimized" in result.text
    assert result.route == "code"
    assert result.improvement_options


def test_analyze_agent_text_prefers_route_hint():
    llm = _FakeLLM(
        [
            "答案：本题应直接计算。",
            "- 增强结论清晰度",
        ]
    )
    result = agent_service.analyze_agent_text(
        "给定 n，输出 n 的平方。",
        route_hint="qa",
        llm=llm,
    )
    assert result.route == "qa"
    assert "直接计算" in result.text

