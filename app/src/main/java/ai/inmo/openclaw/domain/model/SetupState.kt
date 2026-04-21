package ai.inmo.openclaw.domain.model

enum class SetupStep {
    CHECKING_STATUS,
    DOWNLOADING_ROOTFS,
    EXTRACTING_ROOTFS,
    INSTALLING_NODE,
    INSTALLING_OPENCLAW,
    CONFIGURING_BYPASS,
    COMPLETE,
    ERROR;

    val stepLabel: String
        get() = when (this) {
            CHECKING_STATUS -> "Checking status..."
            DOWNLOADING_ROOTFS -> "Downloading Ubuntu rootfs"
            EXTRACTING_ROOTFS -> "Extracting rootfs"
            INSTALLING_NODE -> "Installing Node.js"
            INSTALLING_OPENCLAW -> "Installing OpenClaw"
            CONFIGURING_BYPASS -> "Configuring Bionic Bypass"
            COMPLETE -> "Setup complete"
            ERROR -> "Error"
        }

    val stepNumber: Int
        get() = when (this) {
            CHECKING_STATUS -> 0
            DOWNLOADING_ROOTFS -> 1
            EXTRACTING_ROOTFS -> 2
            INSTALLING_NODE -> 3
            INSTALLING_OPENCLAW -> 4
            CONFIGURING_BYPASS -> 5
            COMPLETE -> 6
            ERROR -> -1
        }
}

data class SetupState(
    val step: SetupStep = SetupStep.CHECKING_STATUS,
    val progress: Double = 0.0,
    val message: String = "",
    val error: String? = null
) {
    val isComplete: Boolean get() = step == SetupStep.COMPLETE
    val hasError: Boolean get() = step == SetupStep.ERROR

    companion object {
        const val TOTAL_STEPS = 6
    }
}
