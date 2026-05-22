package ai.cjym.agentclaw.capability

import ai.cjym.agentclaw.domain.model.NodeFrame

interface NodeCapabilityHandler {
    val name: String
    val commands: List<String>

    suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame
}
