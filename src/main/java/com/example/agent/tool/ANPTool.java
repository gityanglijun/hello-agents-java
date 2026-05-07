package com.example.agent.tool;

import java.util.*;

/**
 * ANP (Agent Network Protocol) 工具 — 智能体网络管理。
 *
 * 对应 Python 版 hello_agents 的 ANPTool，提供：
 *   - 服务注册与发现 (register_service / unregister_service / discover_services)
 *   - 网络节点管理 (add_node)
 *   - 消息路由 (route_message)
 *   - 网络统计 (get_stats)
 *
 * 这是一个概念性实现，用于演示 Agent 网络管理的核心理念，
 * 无需额外依赖（内部基于 JGraphT 做路由）。
 *
 * 使用示例：
 * <pre>
 *   ANPTool tool = new ANPTool();
 *
 *   // 注册服务
 *   tool.run(Map.of("action", "register_service",
 *       "service_id", "calc-1",
 *       "service_type", "calculator",
 *       "endpoint", "http://localhost:5001"));
 *
 *   // 发现服务
 *   tool.run(Map.of("action", "discover_services",
 *       "service_type", "calculator"));
 *
 *   // 添加节点 → 路由消息
 *   tool.run(Map.of("action", "add_node",
 *       "node_id", "agent-1", "endpoint", "http://localhost:5001"));
 *   tool.run(Map.of("action", "route_message",
 *       "from_node", "agent-1", "to_node", "agent-2",
 *       "message", Map.of("text", "Hello")));
 * </pre>
 */
public class ANPTool extends Tool {

    private final ANPDiscovery discovery;
    private final ANPNetwork network;

    // ==================== 构造 ====================

    public ANPTool() {
        this("anp");
    }

    public ANPTool(String name) {
        this(name, null, null);
    }

    /**
     * @param name      工具名称
     * @param discovery 可选的外部 ANPDiscovery 实例
     * @param network   可选的外部 ANPNetwork 实例
     */
    public ANPTool(String name, ANPDiscovery discovery, ANPNetwork network) {
        super(name, "智能体网络管理工具，支持服务发现、节点管理和消息路由。概念性实现。");
        this.discovery = discovery != null ? discovery : new ANPDiscovery();
        this.network = network != null ? network : new ANPNetwork();
    }

    // ==================== Tool 接口 ====================

    @Override
    public String run(Map<String, Object> parameters) {
        String action = ((String) parameters.getOrDefault("action", "")).toLowerCase();
        if (action.isEmpty()) {
            return "❌ 必须指定 action 参数";
        }

        return switch (action) {
            case "register_service"    -> handleRegisterService(parameters);
            case "unregister_service"  -> handleUnregisterService(parameters);
            case "discover_services"   -> handleDiscoverServices(parameters);
            case "add_node"            -> handleAddNode(parameters);
            case "route_message"       -> handleRouteMessage(parameters);
            case "get_stats"           -> handleGetStats();
            default -> "❌ 不支持的操作: " + action
                     + "。可用: register_service, unregister_service, discover_services, "
                     + "add_node, route_message, get_stats";
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作类型: register_service, unregister_service, discover_services, "
                + "add_node, route_message, get_stats", true, null),
            new ToolParameter("service_id", "string",
                "服务 ID（register_service, unregister_service 需要）", false, null),
            new ToolParameter("service_type", "string",
                "服务类型（register_service, discover_services 需要）", false, null),
            new ToolParameter("endpoint", "string",
                "端点地址（register_service, add_node 需要）", false, null),
            new ToolParameter("node_id", "string",
                "节点 ID（add_node 需要）", false, null),
            new ToolParameter("from_node", "string",
                "源节点 ID（route_message 需要）", false, null),
            new ToolParameter("to_node", "string",
                "目标节点 ID（route_message 需要）", false, null),
            new ToolParameter("message", "object",
                "消息内容（route_message 需要）", false, null),
            new ToolParameter("metadata", "object",
                "元数据（register_service, add_node 可选）", false, null)
        );
    }

    // ==================== 操作处理 ====================

    @SuppressWarnings("unchecked")
    private String handleRegisterService(Map<String, Object> params) {
        String serviceId = (String) params.get("service_id");
        String serviceType = (String) params.get("service_type");
        String endpoint = (String) params.get("endpoint");
        Map<String, Object> metadata = params.get("metadata") instanceof Map
                ? (Map<String, Object>) params.get("metadata")
                : Map.of();

        if (serviceId == null || serviceId.isBlank()
                || serviceType == null || serviceType.isBlank()
                || endpoint == null || endpoint.isBlank()) {
            return "❌ register_service 需要 service_id, service_type, endpoint 参数";
        }

        ServiceInfo service = new ServiceInfo(serviceId, serviceType, endpoint, metadata);
        discovery.registerService(service);
        return "✅ 已注册服务 '" + serviceId + "'";
    }

    private String handleUnregisterService(Map<String, Object> params) {
        String serviceId = (String) params.get("service_id");
        if (serviceId == null || serviceId.isBlank()) {
            return "❌ unregister_service 需要 service_id 参数";
        }

        boolean success = discovery.unregisterService(serviceId);
        return success
                ? "✅ 已注销服务 '" + serviceId + "'"
                : "❌ 服务 '" + serviceId + "' 不存在";
    }

    private String handleDiscoverServices(Map<String, Object> params) {
        String serviceType = (String) params.get("service_type");
        List<ServiceInfo> services = discovery.discoverServices(serviceType);

        if (services.isEmpty()) {
            return "没有找到服务" +
                   (serviceType != null && !serviceType.isBlank()
                           ? "（类型: " + serviceType + "）" : "");
        }

        StringBuilder sb = new StringBuilder("找到 ")
                .append(services.size()).append(" 个服务:\n\n");
        for (var svc : services) {
            sb.append("服务ID: ").append(svc.serviceId()).append("\n");
            sb.append("  名称: ").append(svc.serviceName()).append("\n");
            sb.append("  类型: ").append(svc.serviceType()).append("\n");
            sb.append("  端点: ").append(svc.endpoint()).append("\n");
            if (!svc.capabilities().isEmpty()) {
                sb.append("  能力: ").append(String.join(", ", svc.capabilities())).append("\n");
            }
            if (!svc.metadata().isEmpty()) {
                sb.append("  元数据: ").append(svc.metadata()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    @SuppressWarnings("unchecked")
    private String handleAddNode(Map<String, Object> params) {
        String nodeId = (String) params.get("node_id");
        String endpoint = (String) params.get("endpoint");
        Map<String, Object> metadata = params.get("metadata") instanceof Map
                ? (Map<String, Object>) params.get("metadata")
                : Map.of();

        if (nodeId == null || nodeId.isBlank()
                || endpoint == null || endpoint.isBlank()) {
            return "❌ add_node 需要 node_id, endpoint 参数";
        }

        network.addNode(nodeId, endpoint, metadata);
        return "✅ 已添加节点 '" + nodeId + "'";
    }

    @SuppressWarnings("unchecked")
    private String handleRouteMessage(Map<String, Object> params) {
        String fromNode = (String) params.get("from_node");
        String toNode = (String) params.get("to_node");
        Map<String, Object> message = params.get("message") instanceof Map
                ? (Map<String, Object>) params.get("message")
                : Map.of();

        if (fromNode == null || fromNode.isBlank()
                || toNode == null || toNode.isBlank()) {
            return "❌ route_message 需要 from_node, to_node 参数";
        }

        List<String> path = network.routeMessage(fromNode, toNode, message);
        if (path.isEmpty()) {
            return "❌ 无法找到从 '" + fromNode + "' 到 '" + toNode + "' 的路由路径";
        }
        return "消息路由路径: " + String.join(" → ", path);
    }

    private String handleGetStats() {
        Map<String, Object> stats = network.getNetworkStats();
        StringBuilder sb = new StringBuilder("ANP 网络统计:\n");
        for (var entry : stats.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
              .append(entry.getValue()).append("\n");
        }
        sb.append("- 已注册服务: ").append(discovery.count());
        return sb.toString();
    }

    // ==================== 访问器 ====================

    public ANPDiscovery getDiscovery() { return discovery; }
    public ANPNetwork getNetwork() { return network; }
}
