package com.example.agent.store;

/**
 * 存储后端配置，由环境变量驱动。
 * 优先级：System.getenv() > 默认值
 *
 * 使用方式：
 * <pre>
 *   StoreConfig config = StoreConfig.fromEnv();
 *   StoreFactory factory = new StoreFactory(config);
 * </pre>
 */
public class StoreConfig {

    private static final String ENV_VECTOR_STORE_TYPE = "VECTOR_STORE_TYPE";
    private static final String ENV_GRAPH_STORE_TYPE  = "GRAPH_STORE_TYPE";
    private static final String ENV_QDRANT_URL        = "QDRANT_URL";
    private static final String ENV_NEO4J_URI         = "NEO4J_URI";
    private static final String ENV_NEO4J_USER        = "NEO4J_USER";
    private static final String ENV_NEO4J_PASSWORD    = "NEO4J_PASSWORD";

    public final String vectorStoreType;
    public final String graphStoreType;
    public final String qdrantUrl;
    public final String neo4jUri;
    public final String neo4jUser;
    public final String neo4jPassword;

    public StoreConfig(String vectorStoreType, String graphStoreType,
                       String qdrantUrl, String neo4jUri, String neo4jUser, String neo4jPassword) {
        this.vectorStoreType = vectorStoreType != null ? vectorStoreType : "inmemory";
        this.graphStoreType  = graphStoreType  != null ? graphStoreType  : "inmemory";
        this.qdrantUrl       = qdrantUrl       != null ? qdrantUrl       : "http://localhost:6333";
        this.neo4jUri        = neo4jUri        != null ? neo4jUri        : "bolt://localhost:7687";
        this.neo4jUser       = neo4jUser       != null ? neo4jUser       : "neo4j";
        this.neo4jPassword   = neo4jPassword   != null ? neo4jPassword   : "password";
    }

    /** 从环境变量创建 */
    public static StoreConfig fromEnv() {
        return new StoreConfig(
            System.getenv(ENV_VECTOR_STORE_TYPE),
            System.getenv(ENV_GRAPH_STORE_TYPE),
            System.getenv(ENV_QDRANT_URL),
            System.getenv(ENV_NEO4J_URI),
            System.getenv(ENV_NEO4J_USER),
            System.getenv(ENV_NEO4J_PASSWORD)
        );
    }

    /** 默认配置（全 InMemory） */
    public static StoreConfig defaults() {
        return new StoreConfig("inmemory", "inmemory", null, null, null, null);
    }

    public boolean isQdrantEnabled() { return "qdrant".equalsIgnoreCase(vectorStoreType); }
    public boolean isNeo4jEnabled()  { return "neo4j".equalsIgnoreCase(graphStoreType); }
}
