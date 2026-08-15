"""Environment-backed ML worker configuration."""
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

    # A checkpoint is opt-in. A clean checkout always boots the rule engine.
    MODEL_NAME: str = "none"
    # Optional HF token (only needed for private models / first-time push).
    HF_TOKEN: str = ""
    # Shared secret the API gateway uses to authenticate calls.
    ML_WORKER_SECRET: str = ""
    # Optional debug override. Promoted checkpoints normally use their
    # validation-tuned per-label thresholds.
    MODEL_THRESHOLD_OVERRIDE: float | None = None
    # CodeBERT max sequence length; windows beyond this are split.
    MAX_SEQ_LENGTH: int = 512
    # Content-token overlap between adjacent inference windows.
    MODEL_STRIDE: int = 64
    # Max seconds before the request degrades to the fallback engine.
    ML_INFERENCE_TIMEOUT_S: float = 30.0
    # Comma-separated list of CORS origins allowed to call this service
    # from a browser. Empty list disables CORS entirely.
    ML_CORS_ORIGINS: str = "http://localhost:3000,http://localhost:5173"


settings = Settings()
