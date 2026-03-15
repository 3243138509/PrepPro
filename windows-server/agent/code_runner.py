import os
import subprocess
import sys
import tempfile
from pathlib import Path

from .schemas import PythonExecutionReport


def _looks_like_python_executable(path: Path) -> bool:
    name = path.name.lower()
    return name.startswith("python") and path.suffix.lower() == ".exe" or name == "python"


def _resolve_python_executable() -> str:
    env_python = os.getenv("PREPPRO_PYTHON_EXE", "").strip()
    candidates: list[Path] = []
    if env_python:
        candidates.append(Path(env_python))

    if getattr(sys, "frozen", False):
        app_dir = Path(sys.executable).resolve().parent
        candidates.extend(
            [
                app_dir / "python-runtime" / "python.exe",
                app_dir / "python.exe",
            ]
        )
        base_exec = getattr(sys, "_base_executable", "")
        if base_exec:
            candidates.append(Path(base_exec))
    else:
        candidates.append(Path(sys.executable))

    seen: set[str] = set()
    for candidate in candidates:
        key = str(candidate).lower()
        if key in seen:
            continue
        seen.add(key)
        if not candidate.exists():
            continue
        if candidate == Path(env_python):
            return str(candidate)
        if _looks_like_python_executable(candidate):
            return str(candidate)

    if getattr(sys, "frozen", False):
        raise RuntimeError("未找到可用 Python 解释器（请检查 python-runtime/python.exe）。")
    return sys.executable


def run_python_code(code: str, timeout_seconds: float = 3.0) -> PythonExecutionReport:
    text = code.strip()
    if not text:
        return PythonExecutionReport(success=False, summary="未检测到可执行 Python 代码。")

    temp_path: Path | None = None
    try:
        python_executable = _resolve_python_executable()
        with tempfile.NamedTemporaryFile("w", suffix=".py", encoding="utf-8", delete=False) as tmp:
            tmp.write(text)
            temp_path = Path(tmp.name)

        completed = subprocess.run(
            [python_executable, str(temp_path)],
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        success = completed.returncode == 0
        if success:
            summary = "Python 代码运行通过。"
        else:
            summary = f"Python 代码运行失败（exit={completed.returncode}）。"
        return PythonExecutionReport(
            success=success,
            summary=summary,
            return_code=completed.returncode,
            stdout=completed.stdout.strip(),
            stderr=completed.stderr.strip(),
        )
    except subprocess.TimeoutExpired:
        return PythonExecutionReport(success=False, summary=f"Python 代码运行超时（>{timeout_seconds:.1f}s）。")
    except Exception as exc:
        return PythonExecutionReport(success=False, summary=f"Python 代码运行异常: {exc}")
    finally:
        if temp_path is not None:
            try:
                temp_path.unlink(missing_ok=True)
            except Exception:
                pass

