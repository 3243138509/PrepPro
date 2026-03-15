from dataclasses import dataclass


@dataclass
class PythonExecutionReport:
    success: bool
    summary: str
    return_code: int | None = None
    stdout: str = ""
    stderr: str = ""


@dataclass
class AgentAnalyzeOutput:
    text: str
    route: str
    improvement_options: list[str]
    execution_report: PythonExecutionReport | None = None
    model_notice: str = ""

