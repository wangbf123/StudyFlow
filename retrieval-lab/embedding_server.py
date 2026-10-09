"""CPU BGE embeddings with a verified, persistent Hugging Face revision lock.

Bind to localhost. Download failures are fatal; no mock vectors are substituted.
"""
from __future__ import annotations

import hmac
import json
import os
import re
from contextlib import asynccontextmanager
from pathlib import Path
from threading import Lock
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

MODEL_ID = "BAAI/bge-small-zh-v1.5"
ROOT = Path(__file__).resolve().parent
LOCK_PATH = Path(os.getenv("BGE_MODEL_LOCK", str(ROOT / "model-lock.json")))
CACHE = os.getenv("BGE_CACHE_DIR", str(ROOT / "model-cache"))
os.environ.setdefault("HF_HOME", CACHE)
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
model = None
identity = None
inference_lock = Lock()


def resolve_revision() -> str:
    from huggingface_hub import HfApi

    requested = os.getenv("BGE_MODEL_REVISION", "").strip()
    saved = json.loads(LOCK_PATH.read_text()) if LOCK_PATH.exists() else None
    if saved and saved.get("model_id") != MODEL_ID:
        raise RuntimeError("Model lock belongs to a different model")
    if requested:
        if not re.fullmatch(r"[0-9a-f]{40}", requested):
            raise RuntimeError("BGE_MODEL_REVISION must be a full commit SHA, not a branch")
        if saved and saved.get("revision") != requested:
            raise RuntimeError("Requested revision differs from lock; use a new lock and rebuild the index")
        return requested
    if saved:
        revision = saved.get("revision", "")
        if not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise RuntimeError("Invalid persisted model revision")
        return revision
    # First startup resolves the authoritative model repository, then all artifacts use that SHA.
    revision = HfApi().model_info(MODEL_ID).sha
    if not revision or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise RuntimeError("Hugging Face did not return a full model commit SHA")
    return revision


@asynccontextmanager
async def lifespan(_app: FastAPI):
    global model, identity
    import torch
    from sentence_transformers import SentenceTransformer

    torch.set_num_threads(int(os.getenv("BGE_CPU_THREADS", "2")))
    revision = resolve_revision()
    model = SentenceTransformer(MODEL_ID, revision=revision, device="cpu", cache_folder=CACHE,
                                trust_remote_code=False)
    if model.get_sentence_embedding_dimension() != 512:
        raise RuntimeError("Unexpected BGE embedding dimension")
    # Pin only after the model was successfully loaded. Never silently move an existing lock.
    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps({"model_id": MODEL_ID, "revision": revision, "dimension": 512}, indent=2)
    if not LOCK_PATH.exists():
        try:
            with LOCK_PATH.open("x") as handle:
                handle.write(encoded + "\n")
        except FileExistsError:
            if json.loads(LOCK_PATH.read_text()).get("revision") != revision:
                raise RuntimeError("Concurrent startup pinned a different revision")
    identity = f"{MODEL_ID}@{revision}"
    yield
    model = None


app = FastAPI(title="StudyFlow CPU BGE embeddings", lifespan=lifespan)


class EmbeddingRequest(BaseModel):
    model: str = MODEL_ID
    input: str | list[str]
    input_type: Literal["query", "document"] = "document"


def authorize(authorization: str | None) -> None:
    required = os.getenv("BGE_API_KEY", "")
    if required and not hmac.compare_digest(authorization or "", "Bearer " + required):
        raise HTTPException(401, "Invalid sidecar authentication")


@app.get("/health")
def health(authorization: str | None = Header(default=None)):
    authorize(authorization)
    return {"ready": model is not None, "model": identity, "dimension": 512, "device": "cpu",
            "max_tokens": model.max_seq_length if model is not None else None}


@app.post("/embeddings")
def embeddings(request: EmbeddingRequest, authorization: str | None = Header(default=None)):
    authorize(authorization)
    if model is None:
        raise HTTPException(503, "Model has not loaded")
    if request.model not in (MODEL_ID, identity):
        raise HTTPException(400, "Requested model differs from pinned sidecar identity")
    texts = [request.input] if isinstance(request.input, str) else request.input
    if not texts or len(texts) > 64 or any(not text.strip() or len(text) > 8000 for text in texts):
        raise HTTPException(400, "Use 1..64 nonempty inputs of at most 8000 characters")
    if request.input_type == "query":
        texts = [QUERY_PREFIX + text for text in texts]
    # Reject truncation rather than silently dropping evidence from long chunks.
    if any(len(ids) > model.max_seq_length for ids in model.tokenizer(texts, truncation=False)["input_ids"]):
        raise HTTPException(400, "Input exceeds model token limit; use smaller versioned chunks")
    with inference_lock:
        vectors = model.encode(texts, batch_size=16, normalize_embeddings=True,
                               convert_to_numpy=True, show_progress_bar=False)
    return {"object": "list", "model": identity,
            "data": [{"object": "embedding", "index": i, "embedding": vector.tolist()}
                     for i, vector in enumerate(vectors)]}
