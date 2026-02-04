#!/usr/bin/env bash

pkill -f "_specialist.py"

killall python3

fuser -k 50055/tcp 50056/tcp

# Check if some are still running
ps aux | grep specialist