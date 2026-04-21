package ai.inmo.openclaw.capability;

interface ICapabilityService {
    String invoke(String method, String paramsJson);
    boolean isAlive();
}
