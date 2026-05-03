package com.example.agent.tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncToolExecutor implements AutoCloseable {

    private final ToolRegistry registry;
    private final ExecutorService executor;

    public AsyncToolExecutor(ToolRegistry registry, int maxWorkers) {
        this.registry = registry;
        this.executor = Executors.newFixedThreadPool(maxWorkers);
    }

    public AsyncToolExecutor(ToolRegistry registry) {
        this(registry, 4);
    }

    public CompletableFuture<String> executeToolAsync(String toolName, String inputData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return registry.executeTool(toolName, inputData);
            } catch (Exception e) {
                return "❌ 工具执行失败: " + e.getMessage();
            }
        }, executor);
    }

    public List<CompletableFuture<String>> executeToolsParallel(List<Map<String, String>> tasks) {
        System.out.println("🚀 开始并行执行 " + tasks.size() + " 个工具任务");

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Map<String, String> task : tasks) {
            String toolName = task.get("tool_name");
            String inputData = task.get("input_data");
            futures.add(executeToolAsync(toolName, inputData));
        }

        return futures;
    }

    public List<String> executeToolsParallelAndWait(List<Map<String, String>> tasks) {
        List<CompletableFuture<String>> futures = executeToolsParallel(tasks);

        // 等待所有任务完成并收集结果
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        allDone.join();

        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            results.add(f.join());
        }

        System.out.println("✅ 所有工具任务执行完成");
        return results;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
