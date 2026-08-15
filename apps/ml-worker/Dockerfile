# syntax=docker/dockerfile:1
#
# Build context: repository root (render.yaml sets `dockerContext: .`).
# All COPY paths below are relative to that root so the image can ship
# both the worker code AND the canonical taxonomy file together.
FROM python:3.11-slim
WORKDIR /app

# System deps (curl for healthcheck, libyaml for PyYAML wheel).
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        libyaml-0-2 \
    && rm -rf /var/lib/apt/lists/*

# CPU-only PyTorch to keep the image ~500MB instead of ~5GB.
RUN pip install --no-cache-dir \
      --retries 10 --timeout 120 \
      --index-url https://download.pytorch.org/whl/cpu \
      torch==2.12.1+cpu

# Install the rest of the requirements (PyYAML is pinned explicitly).
COPY apps/ml-worker/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --retries 10 --timeout 120 -r /tmp/requirements.txt

# Application code (worker) + canonical taxonomy YAML.
COPY apps/ml-worker/app/ /app/app/
COPY taxonomy/anti_patterns.yaml /app/taxonomy/anti_patterns.yaml

# Create non-root user with a writable HF cache directory.
RUN useradd --create-home --uid 10001 automated-code-review-tool \
    && mkdir -p /home/automated-code-review-tool/.cache/huggingface \
    && chown -R automated-code-review-tool:automated-code-review-tool /app /home/automated-code-review-tool \
    && chmod -R 500 /app

ENV HF_HOME=/home/automated-code-review-tool/.cache/huggingface
ENV MODEL_NAME=none
ENV PYTHONUNBUFFERED=1
USER automated-code-review-tool
EXPOSE 8000

# Health check must come BEFORE the entrypoint.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/ml/health', timeout=2).status==200 else 1)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
