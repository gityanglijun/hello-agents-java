package com.example.agent.store;

/**
 * 存储后端工厂。根据 StoreConfig 创建对应的 VectorStore / GraphStore 实例。
 *
 * 使用方式：
 * <pre>
 *   StoreFactory sf = new StoreFactory();  // 从环境变量读取
 *   VectorStore vs = sf.createVectorStore(256, "episodic");
 *   GraphStore gs = sf.createGraphStore();
 * </pre>
 */
public class StoreFactory {

    private final StoreConfig config;

    public StoreFactory() {
        this(StoreConfig.fromEnv());
    }

    public StoreFactory(StoreConfig config) {
        this.config = config != null ? config : StoreConfig.defaults();
    }

    /**
     * 创建 VectorStore。
     * @param dimension      向量维度（Qdrant collection 创建时使用）
     * @param collectionName 集合名称（Qdrant 中用于区分不同类型的向量存储）
     */
    public VectorStore createVectorStore(int dimension, String collectionName) {
        if (config.isQdrantEnabled()) {
            return new QdrantVectorStore(config.qdrantUrl, dimension, collectionName);
        }
        return new InMemoryVectorStore(dimension);
    }

    /** 创建 GraphStore */
    public GraphStore createGraphStore() {
        if (config.isNeo4jEnabled()) {
            return new Neo4jGraphStore(config.neo4jUri, config.neo4jUser, config.neo4jPassword);
        }
        return new InMemoryGraphStore();
    }

    public StoreConfig getConfig() { return config; }
}
