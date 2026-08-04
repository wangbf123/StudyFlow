FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True \
    PADDLE_PDX_MODEL_SOURCE=BOS \
    OCR_DEVICE=cpu \
    OCR_LANG=ch \
    OCR_DPI=180 \
    OCR_MAX_FILE_MB=80 \
    OCR_MAX_PAGES=100

RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 libgl1 curl \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    "paddlepaddle>=3.2,<4" \
    "paddleocr==3.7.0" \
    "fastapi==0.115.12" \
    "uvicorn[standard]==0.34.2" \
    "python-multipart==0.0.20" \
    "PyMuPDF==1.26.3" \
    "Pillow==11.3.0" \
    "numpy<2"

WORKDIR /app
COPY app.py /app/app.py

EXPOSE 8866
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8866/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8866", "--workers", "1"]
