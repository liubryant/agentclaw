package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame

interface NodeCapabilityHandler {
    val name: String
    val commands: List<String>

    suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame
}
