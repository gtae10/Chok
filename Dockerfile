# ---- Build stage: Gradle로 bootJar 빌드 ----
FROM gradle:8.11-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon -x test

# ---- Runtime stage: JRE + Python(venv) 동시 포함 ----
# DataCollectionService/ModelTrainingService가 ProcessBuilder로
# python-collector/.venv 안의 python을 직접 실행하므로, 같은 컨테이너에
# 두 런타임을 함께 둔다 (PythonEnvironment.pythonExecutable()가 Linux 기준
# ".venv/bin/python" 경로를 계산함).
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-venv python3-pip \
    && rm -rf /var/lib/apt/lists/*

COPY python-collector/ ./python-collector/
RUN python3 -m venv python-collector/.venv \
    && python-collector/.venv/bin/pip install --no-cache-dir -r python-collector/requirements.txt

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
