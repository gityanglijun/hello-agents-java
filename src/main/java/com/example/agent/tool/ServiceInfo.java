package com.example.agent.tool;

import java.util.*;

/**
 * ANP 服务信息 — 描述一个已注册的网络服务。
 */
public class ServiceInfo {

    private final String serviceId;
    private final String serviceName;
    private final String serviceType;
    private final String endpoint;
    private final List<String> capabilities;
    private final Map<String, Object> metadata;

    public ServiceInfo(String serviceId, String serviceType, String endpoint,
                       Map<String, Object> metadata) {
        this(serviceId, serviceType, serviceType, endpoint, List.of(), metadata);
    }

    public ServiceInfo(String serviceId, String serviceType, String endpoint,
                       List<String> capabilities, Map<String, Object> metadata) {
        this(serviceId, serviceType, serviceType, endpoint, capabilities, metadata);
    }

    public ServiceInfo(String serviceId, String serviceName, String serviceType,
                       String endpoint, List<String> capabilities,
                       Map<String, Object> metadata) {
        this.serviceId = serviceId;
        this.serviceName = serviceName != null ? serviceName : serviceType;
        this.serviceType = serviceType;
        this.endpoint = endpoint;
        this.capabilities = capabilities != null ? new ArrayList<>(capabilities) : List.of();
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }

    public String serviceId() { return serviceId; }
    public String serviceName() { return serviceName; }
    public String serviceType() { return serviceType; }
    public String endpoint() { return endpoint; }
    public List<String> capabilities() { return new ArrayList<>(capabilities); }
    public Map<String, Object> metadata() { return new LinkedHashMap<>(metadata); }

    @Override
    public String toString() {
        return "ServiceInfo[id=" + serviceId + ", type=" + serviceType
                + ", endpoint=" + endpoint + "]";
    }
}
