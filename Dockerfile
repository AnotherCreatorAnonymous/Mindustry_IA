# Base ligera de Debian con Python 3.10 preinstalado
FROM python:3.10-slim-bookworm

# Evita que Python escriba archivos .pyc y fuerza a que la salida de consola sea en tiempo real
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# Instalar dependencias del sistema y Java (necesario para Gradle y Mindustry)
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    git \
    && rm -rf /var/lib/apt/lists/*

# Definir la ruta de trabajo dentro del contenedor
WORKDIR /app

# Exponer el puerto para que puedas ver TensorBoard desde Windows
EXPOSE 6006

# Comando por defecto para mantener el contenedor vivo y darte acceso a la terminal
CMD ["/bin/bash"]