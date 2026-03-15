from pathlib import Path

import subprocess

from agent import code_runner


def test_resolve_python_executable_prefers_runtime_python_when_frozen(monkeypatch, tmp_path):
    app_exe = tmp_path / "PrepPro.exe"
    app_exe.write_text("", encoding="utf-8")
    runtime_dir = tmp_path / "python-runtime"
    runtime_dir.mkdir()
    runtime_python = runtime_dir / "python.exe"
    runtime_python.write_text("", encoding="utf-8")

    monkeypatch.setattr(code_runner.sys, "frozen", True, raising=False)
    monkeypatch.setattr(code_runner.sys, "executable", str(app_exe), raising=False)
    monkeypatch.setattr(code_runner.sys, "_base_executable", str(app_exe), raising=False)

    resolved = code_runner._resolve_python_executable()
    assert Path(resolved) == runtime_python


def test_run_python_code_does_not_spawn_preppro_exe_when_frozen(monkeypatch, tmp_path):
    app_exe = tmp_path / "PrepPro.exe"
    app_exe.write_text("", encoding="utf-8")
    runtime_dir = tmp_path / "python-runtime"
    runtime_dir.mkdir()
    runtime_python = runtime_dir / "python.exe"
    runtime_python.write_text("", encoding="utf-8")

    monkeypatch.setattr(code_runner.sys, "frozen", True, raising=False)
    monkeypatch.setattr(code_runner.sys, "executable", str(app_exe), raising=False)
    monkeypatch.setattr(code_runner.sys, "_base_executable", str(app_exe), raising=False)

    called = {}

    def _fake_run(cmd, capture_output, text, timeout, check):
        called["cmd"] = cmd
        return subprocess.CompletedProcess(cmd, 0, stdout="ok\n", stderr="")

    monkeypatch.setattr(code_runner.subprocess, "run", _fake_run)

    result = code_runner.run_python_code("print('ok')")

    assert result.success is True
    assert Path(called["cmd"][0]) == runtime_python
    assert Path(called["cmd"][0]).name.lower() == "python.exe"
