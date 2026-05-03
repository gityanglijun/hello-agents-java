package com.example.agent.tool;
public class MyAdvancedSearchToolTest {

    public static void main(String[] args) throws Exception {
        testAdvancedSearch();
        testApiConfiguration();
        testWithAgent();
    }

    static void testAdvancedSearch() throws Exception {
        System.out.println("🔍 测试高级搜索工具\n");

        ToolRegistry registry = MyAdvancedSearchTool.createAdvancedSearchRegistry();

        String[] queries = {
                "Python编程语言的历史",
                "人工智能的最新发展",
        };

        for (int i = 0; i < queries.length; i++) {
            System.out.println("测试 " + (i + 1) + ": " + queries[i]);
            String result = registry.executeTool("advanced_search", queries[i]);
            System.out.println("结果: " + result + "\n");
            System.out.println("-".repeat(60) + "\n");
        }
    }

    static void testApiConfiguration() throws Exception {
        System.out.println("🔧 测试API配置检查:");

        MyAdvancedSearchTool searchTool = new MyAdvancedSearchTool();
        String result = searchTool.search("机器学习算法");
        System.out.println("搜索结果: " + result);
    }

    static void testWithAgent() {
        System.out.println("\n🤖 与Agent集成测试:");
        System.out.println("高级搜索工具已准备就绪，可以与Agent集成使用");

        ToolRegistry registry = MyAdvancedSearchTool.createAdvancedSearchRegistry();
        String toolsDesc = registry.describeTools();
        System.out.println("工具描述:\n" + toolsDesc);
    }
}
