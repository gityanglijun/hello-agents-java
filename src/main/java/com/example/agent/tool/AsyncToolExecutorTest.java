package com.example.agent.tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AsyncToolExecutorTest {

    public static void main(String[] args) throws Exception {
        // 准备注册表
        ToolRegistry registry = MyCalculatorTool.createCalculatorRegistry();

        // 测试1: 并行执行计算任务
        testParallelCalculation(registry);

        // 测试2: 单个异步执行
        testSingleAsync(registry);

        // 测试3: 等待所有完成
        testWaitAll(registry);

        // 测试4: 混合任务
        testMixedTasks(registry);
    }

    static void testParallelCalculation(ToolRegistry registry) {
        System.out.println("=== 测试1: 并行计算 ===");

        try (AsyncToolExecutor executor = new AsyncToolExecutor(registry, 4)) {
            List<Map<String, String>> tasks = List.of(
                    Map.of("tool_name", "my_calculator", "input_data", "2 + 3"),
                    Map.of("tool_name", "my_calculator", "input_data", "10 * 5"),
                    Map.of("tool_name", "my_calculator", "input_data", "100 / 4"),
                    Map.of("tool_name", "my_calculator", "input_data", "sqrt(16) + 1")
            );

            List<String> results = executor.executeToolsParallelAndWait(tasks);

            for (int i = 0; i < results.size(); i++) {
                System.out.println("任务 " + (i + 1) + " 结果: " + results.get(i));
            }
        }
        System.out.println();
    }

    static void testSingleAsync(ToolRegistry registry) throws Exception {
        System.out.println("=== 测试2: 单个异步执行 ===");

        try (AsyncToolExecutor executor = new AsyncToolExecutor(registry)) {
            CompletableFuture<String> future = executor.executeToolAsync("my_calculator", "sqrt(25) + 3 * 2");

            // 等待结果
            String result = future.get();
            System.out.println("异步计算结果: " + result);

            // 链式处理
            executor.executeToolAsync("my_calculator", "100 - 50")
                    .thenAccept(r -> System.out.println("链式结果: " + r))
                    .join();
        }
        System.out.println();
    }

    static void testWaitAll(ToolRegistry registry) {
        System.out.println("=== 测试3: 全部等待完成 ===");

        try (AsyncToolExecutor executor = new AsyncToolExecutor(registry, 3)) {
            List<CompletableFuture<String>> futures = executor.executeToolsParallel(
                    List.of(
                            Map.of("tool_name", "my_calculator", "input_data", "15 * 3"),
                            Map.of("tool_name", "my_calculator", "input_data", "99 - 33"),
                            Map.of("tool_name", "my_calculator", "input_data", "81 / 9")
                    )
            );

            // 用 allOf 等待全部
            CompletableFuture<Void> allDone = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

            // 全部完成后汇总
            String summary = allDone.thenApply(v -> {
                StringBuilder sb = new StringBuilder("汇总结果:\n");
                for (int i = 0; i < futures.size(); i++) {
                    sb.append("  ").append(i + 1).append(": ").append(futures.get(i).join()).append("\n");
                }
                return sb.toString();
            }).join();

            System.out.print(summary);
        }
        System.out.println();
    }

    static void testMixedTasks(ToolRegistry registry) {
        System.out.println("=== 测试4: 混合耗时模拟 ===");

        try (AsyncToolExecutor executor = new AsyncToolExecutor(registry, 4)) {
            long start = System.currentTimeMillis();

            List<Map<String, String>> tasks = List.of(
                    Map.of("tool_name", "my_calculator", "input_data", "1 + 1"),
                    Map.of("tool_name", "my_calculator", "input_data", "2 + 2"),
                    Map.of("tool_name", "my_calculator", "input_data", "3 + 3"),
                    Map.of("tool_name", "my_calculator", "input_data", "4 + 4")
            );

            List<String> results = executor.executeToolsParallelAndWait(tasks);
            long elapsed = System.currentTimeMillis() - start;

            System.out.println("并行执行 " + tasks.size() + " 个任务耗时: " + elapsed + "ms");
            System.out.println("(如果串行执行会慢得多)");
            for (int i = 0; i < results.size(); i++) {
                System.out.println("  任务 " + (i + 1) + ": " + results.get(i));
            }
        }
        System.out.println();
    }
}
