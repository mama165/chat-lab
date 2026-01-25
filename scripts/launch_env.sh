source venv/bin/activate

export PYTHONPATH=.
export PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python
export TEMPORARY_GLOO_PATCH_DISABLE_RUNTIME_VERSION_CHECK=1

python3 services/monitor.py