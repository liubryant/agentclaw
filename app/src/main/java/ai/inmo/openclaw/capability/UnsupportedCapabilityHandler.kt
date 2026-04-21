package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame

class UnsupportedCapabilityHandler(
    override val name: String,
    override val commands: List<String>,
    private val reason: String
) : NodeCapabilityHandler {
    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        return NodeFrame.response(
            id = "",
            error = mapOf(
                "code" to "NOT_SUPPORTED",
                "message" to reason
            )
        )
    }
}
