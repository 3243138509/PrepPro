import argparse
from pathlib import Path

from ultralytics import YOLO


def parse_args() -> argparse.Namespace:
	parser = argparse.ArgumentParser(description="Export a .pt model to ONNX using Ultralytics.")
	parser.add_argument("--weights", required=True, help="Path to .pt weights file")
	parser.add_argument("--imgsz", type=int, default=640, help="Input image size for export")
	parser.add_argument("--opset", type=int, default=12, help="ONNX opset version")
	parser.add_argument(
		"--dynamic",
		action="store_true",
		help="Enable dynamic axes for batch/height/width",
	)
	parser.add_argument(
		"--simplify",
		action="store_true",
		help="Simplify ONNX graph (requires onnxsim)",
	)
	return parser.parse_args()


def main() -> None:
	args = parse_args()
	weights_path = Path(args.weights).expanduser().resolve()
	if not weights_path.exists():
		raise FileNotFoundError(f"Weights not found: {weights_path}")

	model = YOLO(str(weights_path))
	model.export(
		format="onnx",
		imgsz=args.imgsz,
		opset=args.opset,
		dynamic=args.dynamic,
		simplify=args.simplify,
	)


if __name__ == "__main__":
	main()
