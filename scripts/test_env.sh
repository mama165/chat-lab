#!/usr/bin/env bash
# Useful for testing  from project root
source venv/bin/activate
export PYTHONPATH=$PYTHONPATH:.
export PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python
# This variable disable strict version checking
export TEMPORARY_GLOO_PATCH_DISABLE_RUNTIME_VERSION_CHECK=1

python3 -c "from proto.specialist import specialist_service_pb2; print('✅ Proto import successful!')"