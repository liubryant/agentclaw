package ai.inmo.openclaw.domain.model

enum class StartupStep {
    ENVIRONMENT_PRELOAD,
    MODEL_PREACTIVATE,
    GATEWAY_START,
    NODE_PAIRING,
    COMPLETE,
    ERROR
}
