"""
CodeLens — FastAPI ML worker (Issue #9).

Routes:
    POST /ml/review  — auth required, returns ReviewResponse
    GET  /ml/health  — no auth, returns HealthResponse

Auth: shared-secret via `X-ML-Worker-Secret` header. Verified by an ASGI
middleware that runs before route dispatch so a bad secret never reaches
the handler.

Model is loaded once at startup via the `lifespan` context manager and
stored on `app.state.model`. Tests can override `app.state.model` with
a stub to avoid loading the real 500 MB checkpoint.
"""
from __future__ import annotations

import hmac
import logging
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from app.config import settings
from app.model import CodeLensModel, compute_quality_score
from app.schemas import HealthResponse, ReviewRequest, ReviewResponse

AUTH_HEADER = "x-ml-worker-secret"
HEALTH_PATH = "/ml/health"
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------
# Lifespan — load model once, share via app.state
# ----------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load the model on startup when a real model is configured."""
    model_name = settings.MODEL_NAME
    if not model_name or model_name.strip().lower() in {"test", "none"}:
        app.state.model = None
    else:
        try:
            app.state.model = CodeLensModel()
        except Exception:
            logger.warning("Failed to load model %s", model_name, exc_info=True)
            app.state.model = None
    yield


app = FastAPI(
    title="CodeLens ML Worker",
    version="1.0.0",
    lifespan=lifespan,
)


# ----------------------------------------------------------------------
# Auth middleware (ASGI-level, runs before any route)
# ----------------------------------------------------------------------
@app.middleware("http")
async def verify_secret(request: Request, call_next: Any):
    """Reject any request missing or mismatching X-ML-Worker-Secret.

    Only /ml/health is exempt so monitoring can poll it without holding
    the secret. OpenAPI docs are protected.
    """
    if request.url.path == HEALTH_PATH:
        return await call_next(request)

    # If the server is misconfigured (no secret set), refuse all writes
    # loudly rather than silently letting them through.
    if not settings.ML_WORKER_SECRET:
        return JSONResponse(
            status_code=503,
            content={"detail": "ML_WORKER_SECRET is not configured on the server"},
        )

    provided = request.headers.get(AUTH_HEADER, "")
    # Constant-time comparison to avoid timing side channels.
    if not hmac.compare_digest(provided.encode(), settings.ML_WORKER_SECRET.encode()):
        return JSONResponse(status_code=403, content={"detail": "Forbidden"})
    return await call_next(request)


# ----------------------------------------------------------------------
# Routes
# ----------------------------------------------------------------------
@app.get("/ml/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    model: CodeLensModel | None = getattr(app.state, "model", None)
    model_loaded = model is not None
    model_name = model.model_name if model_loaded else settings.MODEL_NAME
    device = str(model.device) if model_loaded else "cpu"
    return HealthResponse(
        status="ok" if model_loaded else "degraded",
        modelLoaded=model_loaded,
        modelName=model_name,
        device=device,
    )


@app.post("/ml/review", response_model=ReviewResponse)
async def review(req: ReviewRequest) -> ReviewResponse:
    model: CodeLensModel | None = getattr(app.state, "model", None)
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    started = time.perf_counter()
    findings = model.predict(req.diff, req.language)
    elapsed_ms = int((time.perf_counter() - started) * 1000)

    return ReviewResponse(
        findings=findings,
        qualityScore=compute_quality_score(findings),
        processingTimeMs=elapsed_ms,
        windowsProcessed=getattr(model, "last_windows_processed", 1),
    )


# ----------------------------------------------------------------------
# Exception handlers — keep response shape consistent
# ----------------------------------------------------------------------
@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail},
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    # Log internally; return a generic envelope.
    return JSONResponse(
        status_code=500,
        content={"detail": "internal server error"},
    )
