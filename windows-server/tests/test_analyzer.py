import analyzer


def test_normalize_analysis_mode_defaults_to_qa():
    assert analyzer.normalize_analysis_mode("") == "qa"
    assert analyzer.normalize_analysis_mode("chat") == "qa"


def test_normalize_analysis_mode_maps_code_aliases():
    assert analyzer.normalize_analysis_mode("code") == "code"
    assert analyzer.normalize_analysis_mode("Programming") == "code"


def test_normalize_target_language_uses_default_python():
    assert analyzer.normalize_target_language("") == "Python"
    assert analyzer.normalize_target_language(None) == "Python"


def test_normalize_target_language_maps_common_aliases():
    assert analyzer.normalize_target_language("py") == "Python"
    assert analyzer.normalize_target_language("cpp") == "C++"
    assert analyzer.normalize_target_language("js") == "JavaScript"


def test_build_text_analysis_prompt_for_code_mode_mentions_language_and_code():
    prompt = analyzer.build_text_analysis_prompt(
        "请按 ACM 输入输出格式实现",
        "给定一个数组，返回最大子数组和",
        mode="code",
        target_language="Java",
    )
    assert "Java" in prompt
    assert "完整可运行代码" in prompt
    assert "给定一个数组" in prompt


def test_build_text_analysis_prompt_for_qa_mode_mentions_answer():
    prompt = analyzer.build_text_analysis_prompt(
        "",
        "1 + 1 等于几？",
        mode="qa",
        target_language="Python",
    )
    assert "直接给出答案结论" in prompt
    assert "1 + 1 等于几" in prompt
