package ai.cjym.agentclaw.capability;

interface ICapabilityService {
    String invoke(String method, String paramsJson);
    boolean isAlive();
}
