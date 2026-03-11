from ultralytics import YOLO
import torch

model = YOLO(r"d:\desktop\biye\model\yolo11n.pt")
# model = YOLO(r"/home/goona/Desktop/ultralytics/runs/detect/yolo11n_custom2/weights/best.pt")


if __name__ == "__main__":
    device = 0 if torch.cuda.is_available() else "cpu"

    model.train(
        data=r"d:\desktop\biye\model\data.yaml",
        epochs=100,
        batch=16,
        imgsz=640,
        device=device,
        name="yolo11n_custom",  
        save_period=10,
        lr0=0.0001,  # 可以降低学习率做精细调整
    )

