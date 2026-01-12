# Utilisation d'une image Python légère
FROM python:3.11-slim

# Installation des dépendances nécessaires pour m2cgen et scikit-learn
RUN pip install --no-cache-dir pandas scikit-learn m2cgen numpy

# Dossier de travail dans le container
WORKDIR /app

# Par défaut, on lance le script de génération
# Le point d'entrée est défini ici mais peut être surchargé
CMD ["python", "gen/train.py"]