#!/usr/bin/env bash
source venv/bin/activate

export PYTHONPATH=$PYTHONPATH:.
export PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python

echo "--- 🚀 Launching Sidecar Specialists ---"

# Start PDF specialist
python3 services/pdf_specialist.py -id "pdf" -port 50055 -type "pdf" -level "info" &

python3 services/audio_specialist.py -id "audio" -port 50056 -type "audio" -level "info" &

python3 services/image_specialist.py -id "image" -port 50057 -type "image" -level "info" &

#  Keep script alive to view logs
wait