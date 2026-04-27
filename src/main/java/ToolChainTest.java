public class ToolChainTest {

    public static void main(String[] args) throws Exception {
        // 准备注册表（只需要计算器，不需要外部 API）
        ToolRegistry registry = MyCalculatorTool.createCalculatorRegistry();

        // 测试1: 基础工具链 - 多步计算
        testBasicChain(registry);

        // 测试2: 变量传递链
        testVariableChain(registry);

        // 测试3: ToolChainManager
        testChainManager(registry);

        // 测试4: 模板变量缺失异常
        testMissingVariable(registry);
    }

    // ==================== 测试1: 基础工具链 ====================
    static void testBasicChain(ToolRegistry registry) throws Exception {
        System.out.println("=== 测试1: 基础工具链 - 多步计算 ===");

        // 创建一个两步计算链：
        //   步骤1: 计算 15 * 8
        //   步骤2: 把上一步结果 + 32
        ToolChain chain = new ToolChain("double_calc", "两步计算");
        chain.addStep("my_calculator", "15 * 8", "step1");      // → 120
        chain.addStep("my_calculator", "{step1} + 32", "step2"); // → 152

        System.out.println("工具链: " + chain.name());
        System.out.println("描述: " + chain.description());

        String result = chain.execute(registry, "", null);
        System.out.println("最终结果: " + result);
        System.out.println();
    }

    // ==================== 测试2: 变量传递 ====================
    static void testVariableChain(ToolRegistry registry) throws Exception {
        System.out.println("=== 测试2: 变量传递链 ===");

        // 三步链，每步引用上一步结果：
        //   步骤1: 计算 sqrt(100) = 10
        //   步骤2: 10 * 5 = 50
        //   步骤3: 50 + 25 = 75
        ToolChain chain = new ToolChain("three_step", "三步传递");
        chain.addStep("my_calculator", "sqrt(100)", "a");    // → 10
        chain.addStep("my_calculator", "{a} * 5", "b");      // → 50
        chain.addStep("my_calculator", "{b} + 25", "c");     // → 75

        System.out.println("三步链测试:");
        String result = chain.execute(registry, "");
        System.out.println("最终结果: " + result);
        System.out.println("预期结果: 75");
        System.out.println();
    }

    // ==================== 测试3: ToolChainManager ====================
    static void testChainManager(ToolRegistry registry) throws Exception {
        System.out.println("=== 测试3: ToolChainManager ===");

        ToolChainManager manager = new ToolChainManager(registry);

        // 注册多个链
        ToolChain addChain = new ToolChain("add_only", "加法链");
        addChain.addStep("my_calculator", "10 + 20", "r1");
        addChain.addStep("my_calculator", "{r1} + 30", "r2");

        ToolChain mulChain = new ToolChain("mul_only", "乘法链");
        mulChain.addStep("my_calculator", "2 * 3", "r1");
        mulChain.addStep("my_calculator", "{r1} * 4", "r2");
        mulChain.addStep("my_calculator", "{r2} * 5", "r3");

        manager.registerChain(addChain);
        manager.registerChain(mulChain);

        // 列出所有链
        System.out.println("已注册的链: " + manager.listChains());

        // 按名称执行
        String addResult = manager.executeChain("add_only", "");
        System.out.println("加法链结果: " + addResult);

        String mulResult = manager.executeChain("mul_only", "");
        System.out.println("乘法链结果: " + mulResult);

        // 执行不存在的链
        String notFound = manager.executeChain("not_exist", "");
        System.out.println("不存在链: " + notFound);
        System.out.println();
    }

    // ==================== 测试4: 模板变量缺失 ====================
    static void testMissingVariable(ToolRegistry registry) throws Exception {
        System.out.println("=== 测试4: 模板变量缺失异常 ===");

        ToolChain chain = new ToolChain("bad_chain", "变量缺失");
        chain.addStep("my_calculator", "{nonexistent}", "r1");

        try {
            chain.execute(registry, "hello");
            System.out.println("❌ 应该抛出异常但没有");
        } catch (IllegalArgumentException e) {
            System.out.println("✅ 正确捕获缺失变量: " + e.getMessage());
        }
        System.out.println();
    }
}
