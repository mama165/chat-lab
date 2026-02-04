  #!/usr/bin/env bash

  # Exit immediately if a command exits with a non-zero status.
  set -e

  echo "--- 🐍 Setting up Python environment ---"

  # 1. Detect Python version (prefers python3)
  if command -v python3 &>/dev/null; then
      PYTHON_BIN=$(command -v python3)
  elif command -v python &>/dev/null; then
      PYTHON_BIN=$(command -v python)
  else
      echo "❌ Error: Python not found. Please install Python 3."
      exit 1
  fi

  echo "--- 🔍 Using: $($PYTHON_BIN --version) ---"

  # 2. Create virtual environment if it doesn't exist
  if [ ! -d "venv" ]; then
      echo "--- 🛠️ Creating virtual environment... ---"
      $PYTHON_BIN -m venv venv
  else
      echo "--- ✅ Virtual environment already exists. ---"
  fi

  # 3. Activate venv and install dependencies
  # Note: activation is slightly different for some shells, but 'source' works for bash/zsh
  echo "--- 📦 Installing/Updating dependencies... ---"
  source venv/bin/activate

  # Upgrade pip first to avoid issues with older WSL/Ubuntu images
  pip install --upgrade pip --quiet

  if [ -f "requirements.txt" ]; then
      pip install -r requirements.txt
  else
      echo "⚠️ Warning: requirements.txt not found. Skipping installation."
  fi

  echo "--- ✨ Setup complete! ---"
  echo "To activate the environment, run: source venv/bin/activate"