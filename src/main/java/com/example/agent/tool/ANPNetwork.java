package com.example.agent.tool;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import java.util.*;

/**
 * ANP 网络管理 — 管理节点拓扑和消息路由。
 *
 * 使用 JGraphT 维护无向图，BFS 最短路径路由。
 */
public class ANPNetwork {

    /** 节点信息。 */
    public static class Node {
        public final String nodeId;
        public final String endpoint;
        public final Map<String, Object> metadata;

        Node(String nodeId, String endpoint, Map<String, Object> metadata) {
            this.nodeId = nodeId;
            this.endpoint = endpoint;
            this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        }
    }

    private final SimpleGraph<String, DefaultEdge> graph;
    private final Map<String, Node> nodes;

    public ANPNetwork() {
        this.graph = new SimpleGraph<>(DefaultEdge.class);
        this.nodes = new LinkedHashMap<>();
    }

    /** 添加节点到网络。 */
    public synchronized void addNode(String nodeId, String endpoint,
                                      Map<String, Object> metadata) {
        graph.addVertex(nodeId);
        nodes.put(nodeId, new Node(nodeId, endpoint, metadata));

        // 自动连接所有已有节点（全互联拓扑）
        for (String existing : nodes.keySet()) {
            if (!existing.equals(nodeId)) {
                graph.addEdge(nodeId, existing);
            }
        }
    }

    /** 消息路由：返回 from → to 的最短路径节点序列。 */
    public synchronized List<String> routeMessage(String fromNode, String toNode,
                                                   Map<String, Object> message) {
        if (!graph.containsVertex(fromNode)) {
            return List.of(); // 源节点不存在
        }
        if (!graph.containsVertex(toNode)) {
            return List.of(); // 目标节点不存在
        }

        BFSShortestPath<String, DefaultEdge> bfs = new BFSShortestPath<>(graph);
        GraphPath<String, DefaultEdge> path = bfs.getPath(fromNode, toNode);
        if (path == null) {
            return List.of(); // 无路径
        }
        return path.getVertexList();
    }

    /** 获取网络统计信息。 */
    public synchronized Map<String, Object> getNetworkStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("node_count", nodes.size());
        stats.put("edge_count", graph.edgeSet().size());
        stats.put("nodes", new ArrayList<>(nodes.keySet()));

        // 各节点连接数
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (String node : nodes.keySet()) {
            degree.put(node, graph.degreeOf(node));
        }
        stats.put("degree", degree);

        return stats;
    }

    /** 获取节点数。 */
    public int nodeCount() {
        return nodes.size();
    }
}
