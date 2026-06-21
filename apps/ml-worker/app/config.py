"""
CodeLens — ML worker configuration (Issue #9).

Single BaseSettings source-of-truth. Loaded from environment + a .env
file at startup. Importable as `from app.config import settings`.
"""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Service-wide settings.

    All fields are env-driven. Default values are the development
    fallbacks (no real HF_TOKEN, no real secret) — production deploys
    MUST set ML_WORKER_SECRET and HF_TOKEN in the environment.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # HuggingFace model to load for inference.
    MODEL_NAME: str = "tanmay-alpha/codelens-codebert"
    # Optional HF token (only needed for private models / first-time push).
    HF_TOKEN: str = ""
    # Shared secret the API gateway uses to authenticate calls.
    ML_WORKER_SECRET: str = ""
    # Sigmoid threshold for converting logits → binary findings.
    THRESHOLD: float = 0.5
    # CodeBERT max sequence length; windows beyond this are split.
    MAX_SEQ_LENGTH: int = 512


settings = Settings()
