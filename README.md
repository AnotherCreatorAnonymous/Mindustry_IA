# Mindustry_IA

Entorno de entrenamiento y desarrollo de una IA autónoma para Mindustry. Este proyecto implementa una arquitectura híbrida que conecta un servidor *headless* mediante un mod en Java con un agente de Python, todo orquestado y empaquetado en contenedores de Docker.

## Arquitectura del Proyecto

El sistema se divide en tres componentes principales que trabajan en conjunto:

*   **Agente de Python (Cerebro):** Encargado de la toma de decisiones mediante algoritmos de Aprendizaje por Refuerzo (Reinforcement Learning).
*   **Mod Puente (Java):** Un mod personalizado que se inyecta en el servidor de Mindustry para extraer el estado del juego, enviarlo al agente y ejecutar las acciones decididas.
*   **Entorno Dockerizado:** Todo el ecosistema (servidor *headless*, dependencias de Java y Python) corre dentro de un contenedor ligero de Linux (Debian), lo que garantiza que el entrenamiento sea reproducible en cualquier máquina.

## Requisitos Previos

Para ejecutar este proyecto de forma local, necesitas:
*   [Docker Desktop](https://www.docker.com/products/docker-desktop/) (con integración WSL habilitada si estás en Windows).
*   Git para clonar el repositorio.

##  Instalación y Uso

**1. Clonar el repositorio:**
```bash
git clone [https://github.com/TU-USUARIO/Mindustry_IA.git](https://github.com/TU-USUARIO/Mindustry_IA.git)
cd Mindustry_IA
