# Utilisation d'une image Python légère
FROM python:3.11-slim

# Install AI dependencies:
# - pandas & numpy: Data manipulation and numerical structures
# - scikit-learn: Provides the HashingVectorizer to match our Go implementation
# - xgboost: The core Gradient Boosting engine for high-performance classification
# - m2cgen: The transpiler used to convert trained models into pure Go source code
RUN pip install --no-cache-dir pandas scikit-learn m2cgen numpy xgboost

# Dossier de travail dans le container
WORKDIR /app

# Par défaut, on lance le script de génération
# Le point d'entrée est défini ici mais peut être surchargé
CMD ["python", "gen/train.py"]