FROM python:3.11-slim
WORKDIR /app
# The worker runs on CPU in the current deployment. Install PyTorch from its
# CPU-only index first so pip does not pull several gigabytes of CUDA libraries.
RUN pip install --no-cache-dir \
      --retries 10 --timeout 120 \
      --index-url https://download.pytorch.org/whl/cpu \
      torch==2.12.1+cpu
COPY requirements.txt .
RUN pip install --no-cache-dir --retries 10 --timeout 120 -r requirements.txt
COPY app/ ./app/
RUN useradd --create-home --uid 10001 codelens \
    && mkdir -p /home/codelens/.cache/huggingface \
    && chown -R codelens:codelens /app /home/codelens
ENV HF_HOME=/home/codelens/.cache/huggingface
USER codelens
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
# Security hardening
RUN chmod -R 500 /app
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/ml/health', timeout=2).status==200 else 1)" || exit 1
