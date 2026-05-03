package com.example.agent.tool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolChainManager {

    private final ToolRegistry registry;
    private final Map<String, ToolChain> chains;

    public ToolChainManager(ToolRegistry registry) {
        this.registry = registry;
        this.chains = new HashMap<>();
    }

    public void registerChain(ToolChain chain) {
        chains.put(chain.name(), chain);
        System.out.println("✅ 工具链 '" + chain.name() + "' 已注册");
    }

    public String executeChain(String chainName, String inputData) throws Exception {
        return executeChain(chainName, inputData, null);
    }

    public String executeChain(String chainName, String inputData, Map<String, Object> context) throws Exception {
        ToolChain chain = chains.get(chainName);
        if (chain == null) {
            return "❌ 工具链 '" + chainName + "' 不存在";
        }
        return chain.execute(registry, inputData, context);
    }

    public List<String> listChains() {
        return new ArrayList<>(chains.keySet());
    }

    public ToolChain getChain(String name) {
        return chains.get(name);
    }
}
