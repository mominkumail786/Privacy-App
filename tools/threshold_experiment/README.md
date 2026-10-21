# Threshold experiment (offline, on your computer)

These two scripts produced the numbers used for the sensitivity presets (see `docs/RECOGNITION_REPORT.md`).
They run the **real MobileFaceNet model** on a small public set of labelled face photos.

Honest limits: 25 photos of famous people (mostly good quality), 38 same-person pairs and 262
different-person pairs. Faces are found with OpenCV Haar (the app uses ML Kit), and the "degraded"
test is a simulation of a poor phone camera. This is NOT your phone. Use Settings > Calibrate in the app for that.

## Run

```bash
python -m venv venv && . venv/bin/activate       # Windows: venv\Scripts\activate
pip install ai-edge-litert "opencv-python-headless<4.13" "numpy<2.4"
mkdir -p work/deepface/img
cp android/app/src/main/assets/MobileFaceNet.tflite work/          # see README.md to download the model
B=https://raw.githubusercontent.com/serengil/deepface/master/tests/unit/dataset   # MIT licence
curl -o work/deepface/master.csv $B/master.csv
for f in $(awk -F, 'NR>1{print $1; print $2}' work/deepface/master.csv | sort -u); do curl -o work/deepface/img/$f $B/$f; done
python tools/threshold_experiment/measure_thresholds.py
python tools/threshold_experiment/measure_enrollment_check.py
```

The photos are not stored in this repository.
