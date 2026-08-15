"""FastAPI boundary for model-or-fallback code review."""

from __future__ import annotations

import asyncio
import hmac
import logging
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.diff_parser import hunk_added_line_range, parse_diff
from app.fallback_scanner import fallback_scan
from app.model import AutomatedCodeReviewToolModel, compute_quality_score
from app.schemas import Finding, HealthResponse, ReviewRequest, ReviewResponse
from app.taxonomy import load_taxonomy

AUTH_HEADER = "x-ml-worker-secret"
HEALTH_PATH = "/ml/health"
INFERENCE_TIMEOUT_S = settings.ML_INFERENCE_TIMEOUT_S
_SUPPORTED_MODEL_LANGUAGES = {"python", "javascript", "java", "unknown"}
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load only an explicitly configured and compatible checkpoint."""
    app.state.model = None
    app.state.model_load_error = None
    model_name = settings.MODEL_NAME.strip()
    if model_name.lower() not in {"", "none", "test"}:
        try:
            model = AutomatedCodeReviewToolModel()
            app.state.model = model
            if not model.is_healthy:
                app.state.model_load_error = model.compatibility.get("reason")
        except Exception as exc:  # availability is provided by the fallback engine
            app.state.model_load_error = type(exc).__name__
            logger.warning("Model load failed; fallback remains available", exc_info=True)
    yield


app = FastAPI(
    title="automated-code-review-tool ML Worker",
    version="1.0.0",
    lifespan=lifespan,
)

cors_origins = [
    origin.strip()
    for origin in settings.ML_CORS_ORIGINS.split(",")
    if origin.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-ML-Worker-Secret"],
)


@app.middleware("http")
async def verify_secret(request: Request, call_next: Any):
    """Require a configured shared secret for every non-health request."""
    if request.url.path == HEALTH_PATH:
        return await call_next(request)
    if not settings.ML_WORKER_SECRET:
        return JSONResponse(
            status_code=503,
            content={"detail": "ML_WORKER_SECRET is not configured on the server"},
        )
    provided = request.headers.get(AUTH_HEADER, "")
    if not hmac.compare_digest(
        provided.encode("utf-8"), settings.ML_WORKER_SECRET.encode("utf-8")
    ):
        return JSONResponse(status_code=403, content={"detail": "Forbidden"})
    return await call_next(request)


@app.get("/ml/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    model: AutomatedCodeReviewToolModel | None = getattr(app.state, "model", None)
    model_healthy = model is not None and model.is_healthy
    if model is not None and model.is_healthy:
        taxonomy_version = model.taxonomy_version
        model_name = model.model_version
        device = str(model.device)
    else:
        taxonomy_version = load_taxonomy().version
        model_name = "none"
        device = "cpu"
    return HealthResponse(
        status="healthy",
        modelLoaded=model_healthy,
        modelName=model_name,
        device=device,
        engine="model" if model_healthy else "fallback",
        taxonomyVersion=taxonomy_version,
        degradedReason=getattr(app.state, "model_load_error", None),
    )


def _predict_hunks(
    model: AutomatedCodeReviewToolModel,
    request: ReviewRequest,
) -> tuple[list[Finding], int]:
    """Run the checkpoint independently for each parsed hunk."""
    if request.mode == "file":
        prediction = model.predict_hunk(
            request.diff,
            request.language,
            mode="file",
            file_path=request.filePath,
            line_start=1,
            line_end=max(1, len(request.diff.splitlines())),
        )
        return list(prediction.findings), prediction.windows_processed

    findings: list[Finding] = []
    windows_processed = 0
    for hunk in parse_diff(request.diff):
        line_start, line_end = hunk_added_line_range(hunk)
        language = (
            hunk.language
            if hunk.language in _SUPPORTED_MODEL_LANGUAGES
            else "unknown"
        )
        prediction = model.predict_hunk(
            hunk.raw_hunk,
            language,
            mode="diff",
            file_path=hunk.file_path,
            hunk_hash=hunk.hunk_sha256,
            line_start=line_start,
            line_end=line_end,
        )
        findings.extend(prediction.findings)
        windows_processed += prediction.windows_processed
    return findings, max(1, windows_processed)


@app.post("/ml/review", response_model=ReviewResponse)
async def review(req: ReviewRequest) -> ReviewResponse:
    start_time = time.perf_counter()
    model: AutomatedCodeReviewToolModel | None = getattr(app.state, "model", None)

    if model is not None and model.is_healthy:
        try:
            findings, windows_processed = await asyncio.wait_for(
                asyncio.to_thread(_predict_hunks, model, req),
                timeout=INFERENCE_TIMEOUT_S,
            )
            return ReviewResponse(
                findings=findings,
                qualityScore=compute_quality_score(findings),
                processingTimeMs=int((time.perf_counter() - start_time) * 1000),
                windowsProcessed=windows_processed,
                engine="model",
                modelVersion=model.model_version,
                taxonomyVersion=model.taxonomy_version,
            )
        except TimeoutError:
            logger.error(
                "Model inference timed out after %.1f seconds; using fallback",
                INFERENCE_TIMEOUT_S,
            )
        except Exception as exc:
            logger.warning(
                "Model inference failed with %s; using fallback",
                type(exc).__name__,
                exc_info=True,
            )

    response = fallback_scan(
        req.diff,
        req.language,
        mode=req.mode,
        file_path=req.filePath,
    )
    response.processingTimeMs = int((time.perf_counter() - start_time) * 1000)
    return response


@app.exception_handler(HTTPException)
async def http_exception_handler(_request: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})


@app.exception_handler(Exception)
async def unhandled_exception_handler(_request: Request, exc: Exception):
    logger.exception("Unhandled ML worker error", exc_info=exc)
    return JSONResponse(status_code=500, content={"detail": "internal server error"})
