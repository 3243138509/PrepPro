import os
import json
import sys
from pathlib import Path

from dotenv import load_dotenv


# Load local secrets/config from windows-server/.env when present.
load_dotenv(Path(__file__).with_name(".env"))


def _get_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _get_float(name: str, default: float) -> float:
	try:
		return float(os.getenv(name, str(default)))
	except ValueError:
		return default


def _get_bool(name: str, default: bool) -> bool:
	raw = os.getenv(name)
	if raw is None:
		return default
	return raw.strip().lower() in {"1", "true", "yes", "on"}


def _runtime_base_dir() -> Path:
	if getattr(sys, "frozen", False):
		return Path(sys.executable).resolve().parent
	return Path(__file__).resolve().parent


def _pick_existing_path(candidates: list[Path], fallback: Path) -> Path:
	for path in candidates:
		if path.exists():
			return path
	return fallback


def _resolve_from_base(path_value: str, base_dir: Path) -> Path:
	path = Path(path_value).expanduser()
	if path.is_absolute():
		return path
	return (base_dir / path).resolve()


HOST = os.getenv("RC_HOST", "0.0.0.0")
PORT = _get_int("RC_PORT", 5001)
PASSWORD = os.getenv("RC_PASSWORD", "changeme")
SOCKET_TIMEOUT_SECONDS = _get_float("RC_SOCKET_TIMEOUT", 8.0)
MAX_FRAME_SIZE = _get_int("RC_MAX_FRAME_SIZE", 12 * 1024 * 1024)
DEFAULT_JPEG_QUALITY = _get_int("RC_JPEG_QUALITY", 75)

# Keep model endpoint/model name in code; only API key should come from .env.
MODEL_API_URL = "https://api.deepseek.com"
# Prefer DEEPSEEK_API_KEY from docs, fallback to legacy RC_MODEL_API_KEY.
MODEL_API_KEY = os.getenv("DEEPSEEK_API_KEY", os.getenv("RC_MODEL_API_KEY", ""))
MODEL_NAME = "deepseek-chat"
MODEL_TIMEOUT_SECONDS = 9999.0

MODEL_PROFILES_FILE = Path(__file__).with_name("model_profiles.json")


def _default_profile() -> dict[str, str]:
	return {
		"apiUrl": MODEL_API_URL,
		"apiKey": MODEL_API_KEY,
		"modelName": MODEL_NAME,
	}


def _normalize_profile(raw: dict[str, object]) -> dict[str, str] | None:
	api_url = str(raw.get("apiUrl", "")).strip()
	api_key = str(raw.get("apiKey", "")).strip()
	model_name = str(raw.get("modelName", "")).strip()
	if not api_url or not api_key or not model_name:
		return None
	return {
		"apiUrl": api_url,
		"apiKey": api_key,
		"modelName": model_name,
	}


def _load_model_profiles() -> tuple[list[dict[str, str]], int]:
	if not MODEL_PROFILES_FILE.exists():
		return [_default_profile()], 0

	try:
		data = json.loads(MODEL_PROFILES_FILE.read_text(encoding="utf-8"))
		raw_profiles = data.get("profiles", []) if isinstance(data, dict) else []
		profiles: list[dict[str, str]] = []
		if isinstance(raw_profiles, list):
			for item in raw_profiles:
				if isinstance(item, dict):
					profile = _normalize_profile(item)
					if profile is not None:
						profiles.append(profile)
		if not profiles:
			profiles = [_default_profile()]

		active_index = 0
		if isinstance(data, dict):
			try:
				active_index = int(data.get("activeIndex", 0))
			except (TypeError, ValueError):
				active_index = 0
		if active_index < 0 or active_index >= len(profiles):
			active_index = 0
		return profiles, active_index
	except Exception:
		return [_default_profile()], 0


MODEL_PROFILES, ACTIVE_MODEL_INDEX = _load_model_profiles()


def persist_model_profiles() -> None:
	MODEL_PROFILES_FILE.write_text(
		json.dumps(
			{
				"activeIndex": ACTIVE_MODEL_INDEX,
				"profiles": MODEL_PROFILES,
			},
			ensure_ascii=False,
			indent=2,
		),
		encoding="utf-8",
	)


def get_model_profiles() -> list[dict[str, str]]:
	return [dict(item) for item in MODEL_PROFILES]


def get_active_model_index() -> int:
	return ACTIVE_MODEL_INDEX


def get_active_model_profile() -> dict[str, str]:
	if not MODEL_PROFILES:
		return _default_profile()
	index = ACTIVE_MODEL_INDEX
	if index < 0 or index >= len(MODEL_PROFILES):
		index = 0
	return dict(MODEL_PROFILES[index])


def add_model_profile(api_url: str, api_key: str, model_name: str, set_active: bool = True) -> dict[str, object]:
	global ACTIVE_MODEL_INDEX
	profile = _normalize_profile(
		{
			"apiUrl": api_url,
			"apiKey": api_key,
			"modelName": model_name,
		}
	)
	if profile is None:
		raise ValueError("apiUrl/apiKey/modelName is required")

	for index, existing in enumerate(MODEL_PROFILES):
		if (
			existing.get("apiUrl") == profile["apiUrl"]
			and existing.get("apiKey") == profile["apiKey"]
			and existing.get("modelName") == profile["modelName"]
		):
			if set_active:
				ACTIVE_MODEL_INDEX = index
			persist_model_profiles()
			return {
				"profiles": get_model_profiles(),
				"activeIndex": ACTIVE_MODEL_INDEX,
			}

	MODEL_PROFILES.append(profile)
	if set_active:
		ACTIVE_MODEL_INDEX = len(MODEL_PROFILES) - 1
	persist_model_profiles()
	return {
		"profiles": get_model_profiles(),
		"activeIndex": ACTIVE_MODEL_INDEX,
	}


def set_active_model(index: int) -> dict[str, object]:
	global ACTIVE_MODEL_INDEX
	if index < 0 or index >= len(MODEL_PROFILES):
		raise ValueError("invalid model index")
	ACTIVE_MODEL_INDEX = index
	persist_model_profiles()
	return {
		"profiles": get_model_profiles(),
		"activeIndex": ACTIVE_MODEL_INDEX,
	}


def delete_model_profile(index: int) -> dict[str, object]:
	global ACTIVE_MODEL_INDEX
	if index < 0 or index >= len(MODEL_PROFILES):
		raise ValueError("invalid model index")
	if len(MODEL_PROFILES) <= 1:
		raise ValueError("at least one model profile must remain")

	MODEL_PROFILES.pop(index)
	if ACTIVE_MODEL_INDEX == index:
		ACTIVE_MODEL_INDEX = max(0, index - 1)
	elif ACTIVE_MODEL_INDEX > index:
		ACTIVE_MODEL_INDEX -= 1

	persist_model_profiles()
	return {
		"profiles": get_model_profiles(),
		"activeIndex": ACTIVE_MODEL_INDEX,
	}


def update_active_model_name(model_name: str) -> dict[str, object]:
	name = model_name.strip()
	if not name:
		raise ValueError("model name is empty")
	if not MODEL_PROFILES:
		raise ValueError("no model profiles")

	index = ACTIVE_MODEL_INDEX
	if index < 0 or index >= len(MODEL_PROFILES):
		index = 0
	MODEL_PROFILES[index]["modelName"] = name
	persist_model_profiles()
	return {
		"profiles": get_model_profiles(),
		"activeIndex": index,
	}

# OCR pre-scan before sending image to multimodal model.
OCR_ENABLED = _get_bool("RC_OCR_ENABLED", True)
OCR_REQUIRED = _get_bool("RC_OCR_REQUIRED", True)
OCR_MAX_TEXT_CHARS = _get_int("RC_OCR_MAX_TEXT_CHARS", 2000)
_BASE_DIR = _runtime_base_dir()
_DEFAULT_RAPID_EXE = _pick_existing_path(
	[
		_BASE_DIR / "RapidOCR-json_v0.2.0" / "RapidOCR-json.exe",
		_BASE_DIR / "RapidOCR-json.exe",
		Path(__file__).resolve().parent / "RapidOCR-json_v0.2.0" / "RapidOCR-json.exe",
	],
	_BASE_DIR / "RapidOCR-json_v0.2.0" / "RapidOCR-json.exe",
)
OCR_RAPID_EXE_PATH = os.getenv(
	"RC_OCR_RAPID_EXE_PATH",
	str(_DEFAULT_RAPID_EXE),
)
OCR_RAPID_MODELS_PATH = os.getenv(
	"RC_OCR_RAPID_MODELS_PATH",
	str(_pick_existing_path(
		[
			Path(OCR_RAPID_EXE_PATH).expanduser().resolve().parent / "models",
			_BASE_DIR / "RapidOCR-json_v0.2.0" / "models",
			_BASE_DIR / "models",
		],
		Path(OCR_RAPID_EXE_PATH).expanduser().resolve().parent / "models",
	)),
)
OCR_RAPID_TIMEOUT_SECONDS = _get_float("RC_OCR_RAPID_TIMEOUT_SECONDS", 30.0)

# Normalize possible relative env paths against runtime base dir.
OCR_RAPID_EXE_PATH = str(_resolve_from_base(OCR_RAPID_EXE_PATH, _BASE_DIR))
OCR_RAPID_MODELS_PATH = str(_resolve_from_base(OCR_RAPID_MODELS_PATH, _BASE_DIR))

# When enabled, analyze endpoint sends OCR text (not image) to model API.
ANALYZE_USE_OCR_TEXT = _get_bool("RC_ANALYZE_USE_OCR_TEXT", True)

# Clipboard push to mobile client.
CLIPBOARD_POLL_INTERVAL_SECONDS = _get_float("RC_CLIPBOARD_POLL_INTERVAL", 0.6)
CLIPBOARD_MAX_TEXT_CHARS = _get_int("RC_CLIPBOARD_MAX_TEXT_CHARS", 4000)
