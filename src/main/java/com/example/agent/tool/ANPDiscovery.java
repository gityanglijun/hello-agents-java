package com.example.agent.tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ANP 服务发现 — 管理服务注册与查找。
 *
 * 支持：
 *   - register_service: 注册新服务
 *   - unregister_service: 注销服务（按 ID）
 *   - discover_services: 按类型查找服务（可选过滤）
 */
public class ANPDiscovery {

    private final Map<String, ServiceInfo> services = new ConcurrentHashMap<>();

    /** 注册服务。 */
    public void registerService(ServiceInfo service) {
        services.put(service.serviceId(), service);
    }

    /** 注销服务，返回是否成功。 */
    public boolean unregisterService(String serviceId) {
        return services.remove(serviceId) != null;
    }

    /** 按类型发现服务（type 为空则返回全部）。 */
    public List<ServiceInfo> discoverServices(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return new ArrayList<>(services.values());
        }
        return services.values().stream()
                .filter(s -> s.serviceType().equalsIgnoreCase(serviceType))
                .collect(Collectors.toList());
    }

    /** 获取已注册服务总数。 */
    public int count() {
        return services.size();
    }

    /** 获取所有已注册的服务类型。 */
    public Set<String> serviceTypes() {
        return services.values().stream()
                .map(ServiceInfo::serviceType)
                .collect(Collectors.toSet());
    }
}
