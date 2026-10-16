#!/usr/bin/env python3
"""Independent, offline model smoke test. This is NOT Android/device validation.

Requires Python mediapipe==1.1.0, ai-edge-litert==2.1.0, numpy, pillow.
Run setup-tracker-model.sh separately; this script never downloads anything.
Optional --person-image must be a locally supplied, legally usable person image.
"""
import argparse
import hashlib
import importlib.metadata
import json
import runpy
import statistics
import time
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--person-image", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    import mediapipe as mp
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter
    from mediapipe.tasks.metadata import schema_py_generated as schema
    from mediapipe.tasks.metadata import metadata_schema_py_generated as metadata

    root = Path(__file__).resolve().parents[1]
    pins = runpy.run_path(str(root / "scripts/verify-tracker-apk.py"))["MODELS"]
    report = {
        "scope": "host CPU smoke; not Android, dance accuracy, HDR or playback performance",
        "mediapipe": importlib.metadata.version("mediapipe"),
        "litert": importlib.metadata.version("ai-edge-litert"),
        "models": [],
    }
    if args.person_image:
        report["personImage"] = {
            "name": args.person_image.name,
            "sha256": hashlib.sha256(args.person_image.read_bytes()).hexdigest(),
        }
    for filename, (size, expected_hash) in pins.items():
        path = root / "app/src/main" / filename
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        assert len(data) == size and digest == expected_hash, f"Model identity mismatch: {path}"
        with zipfile.ZipFile(path) as archive:
            labels = archive.read("labels.txt").decode().splitlines()
            assert labels[0] == "person" and len(labels) == 90
        interpreter = Interpreter(model_path=str(path), num_threads=1)
        interpreter.allocate_tensors()
        describe = lambda tensors: [
            {"name": t["name"], "shape": t["shape"].tolist(), "dtype": np.dtype(t["dtype"]).name}
            for t in tensors
        ]
        record = {
            "file": path.name, "size": size, "sha256": digest, "labelCount": len(labels),
            "inputs": describe(interpreter.get_input_details()),
            "outputs": describe(interpreter.get_output_details()),
        }
        model = schema.Model.GetRootAsModel(data, 0)
        for index in range(model.MetadataLength()):
            entry = model.Metadata(index)
            if entry.Name() != b"TFLITE_METADATA":
                continue
            meta = metadata.ModelMetadata.GetRootAsModelMetadata(
                model.Buffers(entry.Buffer()).DataAsNumpy(), 0)
            tensor = meta.SubgraphMetadata(0).InputTensorMetadata(0)
            for unit_index in range(tensor.ProcessUnitsLength()):
                unit = tensor.ProcessUnits(unit_index)
                if unit.OptionsType() == metadata.ProcessUnitOptions.NormalizationOptions:
                    norm = metadata.NormalizationOptions()
                    norm.Init(unit.Options().Bytes, unit.Options().Pos)
                    record["normalization"] = {
                        "mean": norm.MeanAsNumpy().tolist(), "std": norm.StdAsNumpy().tolist()}
        assert record.get("normalization") == {"mean": [127.5], "std": [127.5]}
        edge = 448 if "lite2" in path.name else 320
        assert record["inputs"][0]["shape"] == [1, edge, edge, 3]
        assert record["inputs"][0]["dtype"] == "float32"
        # The official float32 models expose raw scores and boxes (not four SSD
        # postprocess outputs). MediaPipe supplies metadata-aware postprocessing.
        assert len(record["outputs"]) == 2
        record["options"] = {"scoreThreshold": 0.22 if edge == 448 else 0.30, "maxResults": 24}
        options = mp.tasks.vision.ObjectDetectorOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(path)),
            running_mode=mp.tasks.vision.RunningMode.IMAGE,
            category_allowlist=["person"], score_threshold=record["options"]["scoreThreshold"],
            max_results=record["options"]["maxResults"],
        )
        with mp.tasks.vision.ObjectDetector.create_from_options(options) as detector:
            blank = mp.Image(image_format=mp.ImageFormat.SRGB,
                             data=np.zeros((448, 448, 3), dtype=np.uint8))
            assert not detector.detect(blank).detections, "Blank image false positive"
            record["blankDetections"] = 0
            if args.person_image:
                photo = mp.Image.create_from_file(str(args.person_image))
                result = detector.detect(photo)
                assert result.detections, "No person found in supplied person fixture"
                record["personDetections"] = [
                    {"score": d.categories[0].score,
                     "box": [d.bounding_box.origin_x, d.bounding_box.origin_y,
                             d.bounding_box.width, d.bounding_box.height]}
                    for d in result.detections
                ]
            samples = []
            for _ in range(5):
                start = time.perf_counter()
                detector.detect(blank)
                samples.append((time.perf_counter() - start) * 1000)
            record["hostWarmBlankMedianMs"] = statistics.median(samples)
        record["result"] = "PASS"
        report["models"].append(record)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
