"""Count the faces in pictures, with the YuNet detector of OpenCV.

YuNet is a small detector (about 230 KB, Apache 2.0 licence): it runs in about
10 ms per picture on a desktop CPU, and OpenCV ships the same model for
Android. The model file is not stored in the repository. Download it once:

    python scripts/count_faces.py --download-model models/

Then count the faces of a folder of pictures:

    python scripts/count_faces.py models/face_detection_yunet_2023mar.onnx FOLDER

The output is one line per picture: the number of faces and their confidence.
A picture without a readable file is reported, not guessed.
"""

import argparse
import glob
import os
import sys

import cv2

MODEL_REPO = "opencv/face_detection_yunet"
MODEL_FILE = "face_detection_yunet_2023mar.onnx"
INPUT_SIZE = (320, 320)
MIN_SCORE = 0.6
NMS_THRESHOLD = 0.3
TOP_K = 5000


def download_model(directory):
    from huggingface_hub import hf_hub_download

    return hf_hub_download(MODEL_REPO, MODEL_FILE, local_dir=directory)


def load_detector(model_path):
    return cv2.FaceDetectorYN.create(model_path, "", INPUT_SIZE, MIN_SCORE, NMS_THRESHOLD, TOP_K)


def count_faces(detector, image_path):
    """Return the list of face confidences in a picture."""
    image = cv2.imread(image_path)
    if image is None:
        raise ValueError("not a readable picture")
    detector.setInputSize((image.shape[1], image.shape[0]))
    _, faces = detector.detect(image)
    if faces is None:
        return []
    return [round(float(score), 2) for score in faces[:, -1]]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model_or_directory")
    parser.add_argument("folder", nargs="?")
    parser.add_argument("--download-model", action="store_true", help="download the model into the given directory")
    args = parser.parse_args(argv)

    if args.download_model:
        print(download_model(args.model_or_directory))
        return 0
    if not args.folder:
        parser.error("give the model file and a folder of pictures")

    detector = load_detector(args.model_or_directory)
    pictures = sorted(
        path for path in glob.glob(os.path.join(args.folder, "*"))
        if path.lower().endswith((".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"))
    )
    for path in pictures:
        try:
            scores = count_faces(detector, path)
            print(f"{os.path.basename(path)}\t{len(scores)} visage(s)\t{scores}")
        except ValueError as error:
            print(f"{os.path.basename(path)}\tignorée : {error}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
