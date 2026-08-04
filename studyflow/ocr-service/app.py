from __future__ import annotations

import json
import os
import threading
import time
from io import BytesIO
from importlib.metadata import PackageNotFoundError, version
from typing import Any

import fitz
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from paddleocr import PaddleOCR
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel


ENGINE_NAME = "PaddleOCR"
OCR_LANG = os.getenv("OCR_LANG", "ch")
OCR_DEVICE = os.getenv("OCR_DEVICE", "cpu")
OCR_DPI = max(96, min(int(os.getenv("OCR_DPI", "180")), 300))
OCR_MAX_FILE_BYTES = max(1, int(os.getenv("OCR_MAX_FILE_MB", "80"))) * 1024 * 1024
OCR_MAX_PAGES = max(1, int(os.getenv("OCR_MAX_PAGES", "100")))

app = FastAPI(title="StudyFlow PaddleOCR Service", version="1.0.0")
_engine: PaddleOCR | None = None
_engine_lock = threading.Lock()
_inference_lock = threading.Lock()


class OcrPage(BaseModel):
    pageNumber: int
    text: str
    confidence: float | None = None


class OcrResponse(BaseModel):
    engine: str
    pageCount: int
    pages: list[OcrPage]
    elapsedMs: int


def _package_version(name: str) -> str:
    try:
        return version(name)
    except PackageNotFoundError:
        return "unknown"


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "UP",
        "engine": ENGINE_NAME,
        "modelLoaded": _engine is not None,
        "paddleocrVersion": _package_version("paddleocr"),
        "device": OCR_DEVICE,
        "language": OCR_LANG,
    }


@app.post("/v1/ocr", response_model=OcrResponse)
def recognize(
    file: UploadFile = File(...),
    pages: str = Form(default=""),
) -> OcrResponse:
    started = time.perf_counter()
    payload = file.file.read(OCR_MAX_FILE_BYTES + 1)
    if not payload:
        raise HTTPException(status_code=400, detail="OCR input file is empty")
    if len(payload) > OCR_MAX_FILE_BYTES:
        raise HTTPException(status_code=413, detail="OCR input exceeds configured size limit")

    file_name = file.filename or "document"
    suffix = file_name.rsplit(".", 1)[-1].lower() if "." in file_name else ""
    try:
        if suffix == "pdf" or file.content_type == "application/pdf":
            total_pages, page_images = _render_pdf(payload, _parse_pages(pages))
        else:
            total_pages, page_images = 1, [(1, _decode_image(payload))]
        recognized_pages = [_recognize_page(page_number, image) for page_number, image in page_images]
    except HTTPException:
        raise
    except (fitz.FileDataError, UnidentifiedImageError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"Unsupported or damaged document: {exc}") from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"PaddleOCR inference failed: {exc}") from exc

    return OcrResponse(
        engine=ENGINE_NAME,
        pageCount=total_pages,
        pages=recognized_pages,
        elapsedMs=round((time.perf_counter() - started) * 1000),
    )


def _get_engine() -> PaddleOCR:
    global _engine
    if _engine is not None:
        return _engine
    with _engine_lock:
        if _engine is None:
            _engine = PaddleOCR(
                lang=OCR_LANG,
                device=OCR_DEVICE,
                use_doc_orientation_classify=True,
                use_doc_unwarping=False,
                use_textline_orientation=True,
            )
    return _engine


def _parse_pages(raw: str) -> list[int] | None:
    if not raw or not raw.strip():
        return None
    try:
        selected = sorted({int(value.strip()) for value in raw.split(",") if value.strip()})
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="pages must be comma-separated positive integers") from exc
    if not selected or selected[0] < 1:
        raise HTTPException(status_code=400, detail="page numbers are 1-based")
    if len(selected) > OCR_MAX_PAGES:
        raise HTTPException(status_code=400, detail="too many OCR pages requested")
    return selected


def _render_pdf(payload: bytes, selected_pages: list[int] | None) -> tuple[int, list[tuple[int, np.ndarray]]]:
    document = fitz.open(stream=payload, filetype="pdf")
    try:
        total_pages = document.page_count
        page_numbers = selected_pages or list(range(1, total_pages + 1))
        if len(page_numbers) > OCR_MAX_PAGES:
            raise HTTPException(status_code=400, detail="PDF exceeds OCR page limit")
        if any(page_number > total_pages for page_number in page_numbers):
            raise HTTPException(status_code=400, detail="requested OCR page exceeds PDF page count")

        rendered: list[tuple[int, np.ndarray]] = []
        matrix = fitz.Matrix(OCR_DPI / 72.0, OCR_DPI / 72.0)
        for page_number in page_numbers:
            page = document.load_page(page_number - 1)
            pixmap = page.get_pixmap(matrix=matrix, alpha=False, colorspace=fitz.csRGB)
            image = np.frombuffer(pixmap.samples, dtype=np.uint8).reshape(pixmap.height, pixmap.width, 3)
            rendered.append((page_number, image))
        return total_pages, rendered
    finally:
        document.close()


def _decode_image(payload: bytes) -> np.ndarray:
    with Image.open(BytesIO(payload)) as image:
        return np.asarray(image.convert("RGB"))


def _recognize_page(page_number: int, image: np.ndarray) -> OcrPage:
    engine = _get_engine()
    with _inference_lock:
        if hasattr(engine, "predict"):
            results = list(engine.predict(input=image))
            lines, scores = _normalize_v3_results(results)
        else:
            results = engine.ocr(image, cls=True)
            lines, scores = _normalize_legacy_results(results)
    confidence = round(sum(scores) / len(scores), 4) if scores else None
    return OcrPage(pageNumber=page_number, text="\n".join(lines).strip(), confidence=confidence)


def _normalize_v3_results(results: list[Any]) -> tuple[list[str], list[float]]:
    lines: list[str] = []
    scores: list[float] = []
    for result in results:
        payload = getattr(result, "json", result)
        if callable(payload):
            payload = payload()
        if isinstance(payload, str):
            payload = json.loads(payload)
        if isinstance(payload, dict) and isinstance(payload.get("res"), dict):
            payload = payload["res"]
        if not isinstance(payload, dict):
            continue
        texts = payload.get("rec_texts") or payload.get("texts") or []
        raw_scores = payload.get("rec_scores") or payload.get("scores") or []
        for index, text in enumerate(texts):
            value = str(text).strip()
            if not value:
                continue
            lines.append(value)
            if index < len(raw_scores):
                try:
                    scores.append(float(raw_scores[index]))
                except (TypeError, ValueError):
                    pass
    return lines, scores


def _normalize_legacy_results(results: Any) -> tuple[list[str], list[float]]:
    lines: list[str] = []
    scores: list[float] = []
    pages = results if isinstance(results, list) else []
    for page in pages:
        if not isinstance(page, list):
            continue
        for item in page:
            if not isinstance(item, (list, tuple)) or len(item) < 2:
                continue
            recognition = item[1]
            if not isinstance(recognition, (list, tuple)) or not recognition:
                continue
            text = str(recognition[0]).strip()
            if text:
                lines.append(text)
            if len(recognition) > 1:
                try:
                    scores.append(float(recognition[1]))
                except (TypeError, ValueError):
                    pass
    return lines, scores
