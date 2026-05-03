package com.example.agent.nlp;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;

import com.example.agent.memory.SemanticMemory;

/**
 * 实体与关系提取器（语义记忆的 NLP 前端）
 *
 * 提取策略：OpenNLP NER（模型可用时）
 *          → 增强词典 + 规则匹配（兜底，开箱即用）
 *
 * 实体类型：person, organization, location, language, framework, tool,
 *          database, paradigm, field, concept, company, algorithm
 * 关系类型：IS_A, USES, PART_OF, CREATES, RUNS_ON, RELATED_TO
 */
public class EntityRelationExtractor {

    // ==================== OpenNLP 状态 ====================

    private boolean opennlpTried;
    private boolean opennlpReady;

    private SentenceDetectorME sentenceDetector;
    private TokenizerME tokenizer;
    private NameFinderME[] nameFinders;
    private String[] nerTypes;

    // ==================== 实体词典（~150 条目，8 大类） ====================

    private static final Map<String, String> ENTITY_DICT = new LinkedHashMap<>();
    static {
        // —— 编程语言 ——
        String LANG = "language";
        ENTITY_DICT.put("Python", LANG);
        ENTITY_DICT.put("Java", LANG);
        ENTITY_DICT.put("JavaScript", LANG);
        ENTITY_DICT.put("TypeScript", LANG);
        ENTITY_DICT.put("Go", LANG);
        ENTITY_DICT.put("Golang", LANG);
        ENTITY_DICT.put("Rust", LANG);
        ENTITY_DICT.put("C", LANG);
        ENTITY_DICT.put("C++", LANG);
        ENTITY_DICT.put("C#", LANG);
        ENTITY_DICT.put("Ruby", LANG);
        ENTITY_DICT.put("PHP", LANG);
        ENTITY_DICT.put("Swift", LANG);
        ENTITY_DICT.put("Kotlin", LANG);
        ENTITY_DICT.put("Scala", LANG);
        ENTITY_DICT.put("R", LANG);
        ENTITY_DICT.put("MATLAB", LANG);
        ENTITY_DICT.put("Lua", LANG);
        ENTITY_DICT.put("Haskell", LANG);
        ENTITY_DICT.put("Clojure", LANG);
        ENTITY_DICT.put("Erlang", LANG);
        ENTITY_DICT.put("Elixir", LANG);
        ENTITY_DICT.put("Dart", LANG);
        ENTITY_DICT.put("Perl", LANG);
        ENTITY_DICT.put("Julia", LANG);
        ENTITY_DICT.put("Shell", LANG);
        ENTITY_DICT.put("Bash", LANG);
        ENTITY_DICT.put("SQL", LANG);

        // —— 编程范式 ——
        String PARA = "paradigm";
        ENTITY_DICT.put("面向对象", PARA);
        ENTITY_DICT.put("OOP", PARA);
        ENTITY_DICT.put("函数式", PARA);
        ENTITY_DICT.put("过程式", PARA);
        ENTITY_DICT.put("响应式", PARA);
        ENTITY_DICT.put("声明式", PARA);
        ENTITY_DICT.put("命令式", PARA);

        // —— 技术领域 ——
        String FIELD = "field";
        ENTITY_DICT.put("机器学习", FIELD);
        ENTITY_DICT.put("深度学习", FIELD);
        ENTITY_DICT.put("自然语言处理", FIELD);
        ENTITY_DICT.put("NLP", FIELD);
        ENTITY_DICT.put("计算机视觉", FIELD);
        ENTITY_DICT.put("CV", FIELD);
        ENTITY_DICT.put("数据科学", FIELD);
        ENTITY_DICT.put("人工智能", FIELD);
        ENTITY_DICT.put("Web开发", FIELD);
        ENTITY_DICT.put("前端开发", FIELD);
        ENTITY_DICT.put("后端开发", FIELD);
        ENTITY_DICT.put("嵌入式开发", FIELD);
        ENTITY_DICT.put("DevOps", FIELD);
        ENTITY_DICT.put("网络安全", FIELD);
        ENTITY_DICT.put("大数据", FIELD);
        ENTITY_DICT.put("云计算", FIELD);
        ENTITY_DICT.put("区块链", FIELD);
        ENTITY_DICT.put("RAG", FIELD);
        ENTITY_DICT.put("检索增强生成", FIELD);

        // —— 框架/库 ——
        String FW = "framework";
        ENTITY_DICT.put("Spring", FW);
        ENTITY_DICT.put("Spring Boot", FW);
        ENTITY_DICT.put("Django", FW);
        ENTITY_DICT.put("Flask", FW);
        ENTITY_DICT.put("FastAPI", FW);
        ENTITY_DICT.put("React", FW);
        ENTITY_DICT.put("Vue", FW);
        ENTITY_DICT.put("Angular", FW);
        ENTITY_DICT.put("TensorFlow", FW);
        ENTITY_DICT.put("PyTorch", FW);
        ENTITY_DICT.put("Keras", FW);
        ENTITY_DICT.put("Scikit-learn", FW);
        ENTITY_DICT.put("Pandas", FW);
        ENTITY_DICT.put("NumPy", FW);
        ENTITY_DICT.put("Node.js", FW);
        ENTITY_DICT.put("Express", FW);
        ENTITY_DICT.put("jQuery", FW);
        ENTITY_DICT.put("Bootstrap", FW);
        ENTITY_DICT.put("Hibernate", FW);
        ENTITY_DICT.put("MyBatis", FW);
        ENTITY_DICT.put("Maven", FW);
        ENTITY_DICT.put("Gradle", FW);

        // —— 数据库 ——
        String DB = "database";
        ENTITY_DICT.put("MySQL", DB);
        ENTITY_DICT.put("PostgreSQL", DB);
        ENTITY_DICT.put("MongoDB", DB);
        ENTITY_DICT.put("Redis", DB);
        ENTITY_DICT.put("Oracle", DB);
        ENTITY_DICT.put("SQLite", DB);
        ENTITY_DICT.put("Elasticsearch", DB);
        ENTITY_DICT.put("Cassandra", DB);
        ENTITY_DICT.put("Neo4j", DB);
        ENTITY_DICT.put("Qdrant", DB);
        ENTITY_DICT.put("Milvus", DB);
        ENTITY_DICT.put("HBase", DB);

        // —— 工具/平台 ——
        String TOOL = "tool";
        ENTITY_DICT.put("Docker", TOOL);
        ENTITY_DICT.put("Kubernetes", TOOL);
        ENTITY_DICT.put("K8s", TOOL);
        ENTITY_DICT.put("Git", TOOL);
        ENTITY_DICT.put("GitHub", TOOL);
        ENTITY_DICT.put("GitLab", TOOL);
        ENTITY_DICT.put("Jenkins", TOOL);
        ENTITY_DICT.put("Nginx", TOOL);
        ENTITY_DICT.put("Apache", TOOL);
        ENTITY_DICT.put("Tomcat", TOOL);
        ENTITY_DICT.put("Linux", TOOL);
        ENTITY_DICT.put("JVM", TOOL);
        ENTITY_DICT.put("JDK", TOOL);
        ENTITY_DICT.put("Vosk", TOOL);
        ENTITY_DICT.put("Whisper", TOOL);
        ENTITY_DICT.put("Tika", TOOL);
        ENTITY_DICT.put("LLM", TOOL);
        ENTITY_DICT.put("API", TOOL);
        ENTITY_DICT.put("REST", TOOL);
        ENTITY_DICT.put("GraphQL", TOOL);
        ENTITY_DICT.put("HTTP", TOOL);
        ENTITY_DICT.put("HTTPS", TOOL);
        ENTITY_DICT.put("JSON", TOOL);
        ENTITY_DICT.put("XML", TOOL);
        ENTITY_DICT.put("YAML", TOOL);
        ENTITY_DICT.put("CSS", TOOL);
        ENTITY_DICT.put("HTML", TOOL);

        // —— 概念 ——
        String CONCEPT = "concept";
        ENTITY_DICT.put("微服务", CONCEPT);
        ENTITY_DICT.put("单体架构", CONCEPT);
        ENTITY_DICT.put("编译器", CONCEPT);
        ENTITY_DICT.put("解释器", CONCEPT);
        ENTITY_DICT.put("向量数据库", CONCEPT);
        ENTITY_DICT.put("知识图谱", CONCEPT);
        ENTITY_DICT.put("语义搜索", CONCEPT);
        ENTITY_DICT.put("嵌入", CONCEPT);
        ENTITY_DICT.put("向量化", CONCEPT);
        ENTITY_DICT.put("分词", CONCEPT);
        ENTITY_DICT.put("哈希", CONCEPT);
        ENTITY_DICT.put("排序", CONCEPT);
        ENTITY_DICT.put("索引", CONCEPT);
        ENTITY_DICT.put("缓存", CONCEPT);
        ENTITY_DICT.put("降级", CONCEPT);
        ENTITY_DICT.put("高可用", CONCEPT);
        ENTITY_DICT.put("跨平台", CONCEPT);
        ENTITY_DICT.put("设计模式", CONCEPT);

        // —— 公司/组织 ——
        String ORG = "organization";
        ENTITY_DICT.put("Google", ORG);
        ENTITY_DICT.put("Microsoft", ORG);
        ENTITY_DICT.put("Apple", ORG);
        ENTITY_DICT.put("Amazon", ORG);
        ENTITY_DICT.put("Meta", ORG);
        ENTITY_DICT.put("OpenAI", ORG);
        ENTITY_DICT.put("Anthropic", ORG);
        ENTITY_DICT.put("阿里巴巴", ORG);
        ENTITY_DICT.put("腾讯", ORG);
        ENTITY_DICT.put("字节跳动", ORG);
        ENTITY_DICT.put("百度", ORG);
        ENTITY_DICT.put("Apache", ORG);
        ENTITY_DICT.put("Oracle", ORG);
        ENTITY_DICT.put("Sun Microsystems", ORG);

        // —— 知名人物 ——
        String PERSON = "person";
        ENTITY_DICT.put("Guido van Rossum", PERSON);
        ENTITY_DICT.put("Linus Torvalds", PERSON);
        ENTITY_DICT.put("James Gosling", PERSON);
        ENTITY_DICT.put("Brendan Eich", PERSON);
        ENTITY_DICT.put("Dennis Ritchie", PERSON);
        ENTITY_DICT.put("Bjarne Stroustrup", PERSON);
        ENTITY_DICT.put("Yann LeCun", PERSON);
        ENTITY_DICT.put("Geoffrey Hinton", PERSON);
    }

    // ==================== 正则实体模式 ====================

    private static final Pattern PAT_DATE = Pattern.compile(
            "\\b\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?\\b");
    private static final Pattern PAT_VERSION = Pattern.compile(
            "\\bv?\\d+\\.\\d+(\\.\\d+)?([-.]?(alpha|beta|rc|release|SNAPSHOT|GA|LTS))?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    private static final Pattern PAT_URL = Pattern.compile(
            "https?://[\\w./-]+");
    private static final Pattern PAT_CAPITALIZED_SEQ = Pattern.compile(
            "\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+\\b");  // 连续大写开头词 → 专有名词

    // ==================== 公开 API ====================

    /** 提取实体 */
    public List<SemanticMemory.Entity> extractEntities(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        if (tryOpenNLP()) {
            List<SemanticMemory.Entity> result = extractEntitiesWithOpenNLP(text);
            if (!result.isEmpty()) return result;
        }
        return extractEntitiesWithDictionary(text);
    }

    /** 提取关系 */
    public List<SemanticMemory.Relation> extractRelations(
            String text, List<SemanticMemory.Entity> textEntities, String memoryId) {
        if (textEntities.size() < 2) return Collections.emptyList();
        return extractRelationsWithPatterns(text, textEntities, memoryId);
    }

    // ==================== OpenNLP 路径 ====================

    // NER 模型文件名（精确匹配，需下载到 models/opennlp/）
    private static final String[] NER_MODEL_FILES = {
        "en-ner-person.bin", "en-ner-location.bin",
        "en-ner-organization.bin", "en-ner-date.bin",
        "en-ner-time.bin", "en-ner-money.bin"
    };
    private static final String[] NER_TYPES = {
        "person", "location", "organization", "date", "time", "money"
    };

    private boolean tryOpenNLP() {
        if (opennlpTried) return opennlpReady;
        opennlpTried = true;

        try {
            Path modelsDir = Paths.get("models", "opennlp");
            if (!Files.isDirectory(modelsDir)) {
                System.out.println("[NER] models/opennlp/ 目录不存在，使用增强词典 (150+ 条目)");
                System.out.println("[NER] 下载模型可启用 NER: https://opennlp.apache.org/models.html");
                return false;
            }

            // 1. 按前缀匹配句子模型（兼容不同版本号）
            Path sentPath = findModelByPrefix(modelsDir, "opennlp-en-ud-ewt-sentence");
            Path tokenPath = findModelByPrefix(modelsDir, "opennlp-en-ud-ewt-tokens");
            if (sentPath == null || tokenPath == null) {
                System.out.println("[NER] UD 模型缺失，使用增强词典 (150+ 条目)");
                System.out.println("[NER] 需下载 opennlp-en-ud-ewt-sentence-*.bin 和 "
                        + "opennlp-en-ud-ewt-tokens-*.bin 到 models/opennlp/");
                return false;
            }
            try (InputStream si = Files.newInputStream(sentPath);
                 InputStream ti = Files.newInputStream(tokenPath)) {
                sentenceDetector = new SentenceDetectorME(new SentenceModel(si));
                tokenizer = new TokenizerME(new TokenizerModel(ti));
            }
            System.out.println("[NER] 句子模型: " + sentPath.getFileName());
            System.out.println("[NER] 分词模型: " + tokenPath.getFileName());

            // 2. 加载 NER 模型
            nameFinders = new NameFinderME[NER_MODEL_FILES.length];
            nerTypes = NER_TYPES;
            int loadedNer = 0;
            for (int i = 0; i < NER_MODEL_FILES.length; i++) {
                Path modelPath = modelsDir.resolve(NER_MODEL_FILES[i]);
                if (Files.exists(modelPath)) {
                    try (InputStream in = Files.newInputStream(modelPath)) {
                        nameFinders[i] = new NameFinderME(new TokenNameFinderModel(in));
                        loadedNer++;
                    }
                }
            }

            opennlpReady = true;
            System.out.println("[NER] OpenNLP 就绪 (句子+分词 + " + loadedNer + "/" + NER_MODEL_FILES.length + " NER)");
            return true;

        } catch (Exception e) {
            System.out.println("[NER] OpenNLP 加载失败: " + e.getMessage());
        }
        return false;
    }

    /** 在目录中查找以 prefix 开头、.bin 结尾的文件（兼容不同版本号） */
    private Path findModelByPrefix(Path dir, String prefix) throws IOException {
        try (var stream = Files.newDirectoryStream(dir, prefix + "*.bin")) {
            for (Path p : stream) {
                return p; // 返回第一个匹配
            }
        }
        return null;
    }

    private List<SemanticMemory.Entity> extractEntitiesWithOpenNLP(String text) {
        List<SemanticMemory.Entity> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String[] sentences = sentenceDetector.sentDetect(text);

            for (String sentence : sentences) {
                String[] tokens = tokenizer.tokenize(sentence);

                for (int i = 0; i < nameFinders.length; i++) {
                    if (nameFinders[i] == null) continue;
                    opennlp.tools.util.Span[] spans = nameFinders[i].find(tokens);
                    for (opennlp.tools.util.Span span : spans) {
                        String name = String.join(" ",
                                Arrays.copyOfRange(tokens, span.getStart(), span.getEnd()));
                        String nerType = nerTypes[i];
                        // 过滤英文 NER 在中英混合文本上的误识别
                        if (!isValidNerResult(name, nerType)) continue;
                        String key = name + ":" + nerType;
                        if (seen.add(key)) {
                            result.add(new SemanticMemory.Entity(
                                    UUID.randomUUID().toString(), name, nerType));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[NER] OpenNLP 提取失败: " + e.getMessage());
        }

        // 补充词典实体（OpenNLP 覆盖不到的中文术语、框架名等）
        List<SemanticMemory.Entity> dictEntities = extractEntitiesWithDictionary(text);
        for (SemanticMemory.Entity de : dictEntities) {
            String key = de.name + ":" + de.type;
            if (seen.add(key)) {
                result.add(de);
            }
        }

        return result;
    }

    /**
     * 过滤英文 NER 模型在中英混合文本上的误识别：
     * - 跨度超过 60 字符 → 拒绝
     * - date/time/money 包含中文 → 拒绝（英文模型不认中文日期）
     * - 纯标点/空白 → 拒绝
     */
    private boolean isValidNerResult(String name, String nerType) {
        if (name == null || name.isBlank() || name.length() > 60) return false;
        boolean hasCjk = name.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
        // 英文 date/time/money 模型无法正确识别中文日期表达式
        if (hasCjk && (nerType.equals("date") || nerType.equals("time") || nerType.equals("money"))) {
            return false;
        }
        // 纯空白/标点
        if (name.trim().replaceAll("[\\s\\p{Punct}]", "").isEmpty()) return false;
        return true;
    }

    // ==================== 增强词典路径（兜底） ====================

    private List<SemanticMemory.Entity> extractEntitiesWithDictionary(String text) {
        List<SemanticMemory.Entity> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. 词典匹配（按长度降序，优先匹配长词）
        List<Map.Entry<String, String>> sorted = new ArrayList<>(ENTITY_DICT.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        String lower = text.toLowerCase();
        for (Map.Entry<String, String> entry : sorted) {
            String term = entry.getKey();
            String type = entry.getValue();
            if (containsTerm(lower, term)) {
                String key = term + ":" + type;
                if (seen.add(key)) {
                    result.add(new SemanticMemory.Entity(
                            UUID.randomUUID().toString(), term, type));
                }
            }
        }

        // 2. 大写专有名词（连续大写开头的英文词）
        Matcher capMatcher = PAT_CAPITALIZED_SEQ.matcher(text);
        while (capMatcher.find()) {
            String name = capMatcher.group();
            if (name.length() >= 5 && !ENTITY_DICT.containsKey(name)) {
                String type = inferEntityType(name, text);
                String key = name + ":" + type;
                if (seen.add(key)) {
                    result.add(new SemanticMemory.Entity(
                            UUID.randomUUID().toString(), name, type));
                }
            }
        }

        // 3. 版本号 → tool
        Matcher verMatcher = PAT_VERSION.matcher(text);
        while (verMatcher.find()) {
            String ver = verMatcher.group();
            if (ver.length() >= 3) {
                String key = ver + ":tool";
                if (seen.add(key)) {
                    result.add(new SemanticMemory.Entity(
                            UUID.randomUUID().toString(), ver, "tool"));
                }
            }
        }

        // 4. 日期 → date
        Matcher dateMatcher = PAT_DATE.matcher(text);
        while (dateMatcher.find()) {
            String date = dateMatcher.group();
            String key = date + ":date";
            if (seen.add(key)) {
                result.add(new SemanticMemory.Entity(
                        UUID.randomUUID().toString(), date, "date"));
            }
        }

        return result;
    }

    /** 根据上下文推断未知专有名词的类型 */
    private String inferEntityType(String name, String text) {
        String lower = text.toLowerCase();
        String nameLower = name.toLowerCase();
        // 找到 name 在文本中的上下文窗口
        int idx = lower.indexOf(nameLower);
        if (idx < 0) return "concept";

        int start = Math.max(0, idx - 40);
        int end = Math.min(lower.length(), idx + name.length() + 40);
        String ctx = lower.substring(start, end);

        if (ctx.contains("语言") || ctx.contains("language") || ctx.contains("编程")) return "language";
        if (ctx.contains("框架") || ctx.contains("framework") || ctx.contains("库")) return "framework";
        if (ctx.contains("数据库") || ctx.contains("database") || ctx.contains("db")) return "database";
        if (ctx.contains("公司") || ctx.contains("company") || ctx.contains("inc") || ctx.contains("组织"))
            return "organization";
        if (ctx.contains("算法") || ctx.contains("algorithm") || ctx.contains("模型")) return "algorithm";
        return "concept";
    }

    // ==================== 关系提取 ====================

    /**
     * 基于句子的关系提取：
     * 1. 拆分句子
     * 2. 同一句内实体两两建立关系（谓语由上下文推断）
     * 3. 相邻句实体也建立弱关系
     */
    private List<SemanticMemory.Relation> extractRelationsWithPatterns(
            String text, List<SemanticMemory.Entity> textEntities, String memoryId) {

        List<SemanticMemory.Relation> result = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();

        List<String> sentences = splitSentences(text);
        Map<String, List<SemanticMemory.Entity>> sentenceEntities = new LinkedHashMap<>();

        // 为每个句子分配实体
        for (String sentence : sentences) {
            List<SemanticMemory.Entity> ents = new ArrayList<>();
            String sLower = sentence.toLowerCase();
            for (SemanticMemory.Entity entity : textEntities) {
                if (containsTerm(sLower, entity.name)) {
                    ents.add(entity);
                }
            }
            sentenceEntities.put(sentence, ents);
        }

        // 同一句内 → 强关系
        for (Map.Entry<String, List<SemanticMemory.Entity>> entry : sentenceEntities.entrySet()) {
            String sentence = entry.getKey();
            List<SemanticMemory.Entity> ents = entry.getValue();
            for (int i = 0; i < ents.size(); i++) {
                for (int j = i + 1; j < ents.size(); j++) {
                    SemanticMemory.Entity a = ents.get(i), b = ents.get(j);
                    if (a.name.equals(b.name)) continue;
                    String pairKey = a.name + "|" + b.name;
                    if (seenPairs.add(pairKey)) {
                        String predicate = inferPredicateEnhanced(sentence, a.name, b.name);
                        result.add(new SemanticMemory.Relation(
                                UUID.randomUUID().toString(),
                                a.entityId, predicate, b.entityId, memoryId));
                    }
                }
            }
        }

        // 相邻句 → 弱关系（仅 RELATED_TO）
        String[] sentArray = sentences.toArray(new String[0]);
        for (int i = 0; i < sentArray.length - 1; i++) {
            List<SemanticMemory.Entity> cur = sentenceEntities.get(sentArray[i]);
            List<SemanticMemory.Entity> next = sentenceEntities.get(sentArray[i + 1]);
            for (SemanticMemory.Entity a : cur) {
                for (SemanticMemory.Entity b : next) {
                    if (a.name.equals(b.name)) continue;
                    String pairKey = a.name + "|" + b.name;
                    if (seenPairs.add(pairKey)) {
                        result.add(new SemanticMemory.Relation(
                                UUID.randomUUID().toString(),
                                a.entityId, "RELATED_TO", b.entityId, memoryId));
                    }
                }
            }
        }

        return result;
    }

    // ==================== 谓语推断（增强版） ====================

    /**
     * 谓语推断：从两个实体周围的文本中识别语义关系。
     *
     * 中英文混合模式，按优先级匹配：
     *   IS_A:    X是Y / X是一种Y / X is a Y / X is an Y
     *   USES:    X使用Y / X用Y / X基于Y / X采用Y / X uses Y
     *   PART_OF: X是Y的一部分 / X属于Y / X is part of Y
     *   CREATES: X创建Y / X开发Y / X发明Y / X creates Y
     *   RUNS_ON: X运行在Y / X runs on Y / X部署在Y
     */
    private String inferPredicateEnhanced(String text, String entityA, String entityB) {
        // 提取两个实体之间的文本（最重要的判断区域）
        String window = findContextWindow(text, entityA, entityB);
        if (window == null || window.isBlank()) {
            window = text;
        }
        String lower = window.toLowerCase();
        String aLower = entityA.toLowerCase();
        String bLower = entityB.toLowerCase();

        // ── IS_A ──
        if (matchesAny(lower,
                aLower + "是", aLower + "是一种", aLower + "属于",
                aLower + " is a ", aLower + " is an ",
                aLower + "被看作", aLower + "被视为",
                "被称作", "被称为")) {
            return "IS_A";
        }
        // 反向：Y是一种X
        if (matchesAny(lower,
                bLower + "是", bLower + "是一种",
                bLower + " is a ", bLower + " is an ")) {
            return "IS_A";
        }

        // ── USES ──
        if (matchesAny(lower,
                "使用", "基于", "采用", "利用", "借助", "依赖",
                " uses ", " using ", " with ", " adopts ", " powered by ",
                " built on ", " relies on ")) {
            return "USES";
        }

        // ── PART_OF ──
        if (matchesAny(lower,
                "组成部分", "属于", "包含", "是……的一部分",
                " is part of ", " belongs to ", " contained in ",
                "由……组成", "纳入")) {
            return "PART_OF";
        }

        // ── CREATES ──
        if (matchesAny(lower,
                "创建", "开发", "发明", "设计", "构建", "推出", "发布",
                " creates ", " developed ", " built ", " invented ",
                " designed ", " launched ", " released ")) {
            return "CREATES";
        }

        // ── RUNS_ON ──
        if (matchesAny(lower,
                "运行在", "部署在", "跑在", "执行于",
                " runs on ", " deployed on ", " hosted on ",
                " supported on ", " compatible with ")) {
            return "RUNS_ON";
        }

        // ── 默认：共现 ──
        return "CO_OCCURS_WITH";
    }

    /** 窗口内匹配任意模式 */
    private boolean matchesAny(String text, String... patterns) {
        for (String p : patterns) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    /**
     * 提取两个实体在文本中的上下文窗口。
     * 取两者之间及前后各 N 个字符的文本。
     */
    private String findContextWindow(String text, String entityA, String entityB) {
        String lower = text.toLowerCase();
        String aLower = entityA.toLowerCase();
        String bLower = entityB.toLowerCase();

        int posA = indexOfTerm(lower, entityA);
        int posB = indexOfTerm(lower, entityB);
        if (posA < 0 || posB < 0) return null;

        int start = Math.min(posA, posB);
        int end = Math.max(posA, posB) + Math.max(entityA.length(), entityB.length());

        // 向前后各扩展 60 字符
        start = Math.max(0, start - 60);
        end = Math.min(lower.length(), end + 60);

        return text.substring(start, end);
    }

    // ==================== 句子分割 ====================

    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        // 中英文句子边界
        String[] raw = text.split("(?<=[.。!！?？\\n])\\s*");
        List<String> result = new ArrayList<>();
        for (String s : raw) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    // ==================== 工具方法 ====================

    /** 从混合 token（如 "Python是一种"）中提取纯 ASCII 前缀 */
    static String extractAsciiPrefix(String token) {
        StringBuilder sb = new StringBuilder();
        for (char c : token.toCharArray()) {
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 是否需要词边界匹配：短 ASCII 词（≤4 字符，单字无空格）。
     * 此类术语容易作为子串误命中，如 "C"→"Docker", "Go"→"Golang", "SQL"→"PostgreSQL"。
     */
    private static boolean needsWordBoundary(String term) {
        if (term.length() > 4) return false;
        if (term.contains(" ")) return false;
        return term.chars().allMatch(c -> c > 0x20 && c < 0x80);
    }

    /** 定位术语在文本中的位置 */
    private static int indexOfTerm(String lowerText, String term) {
        String termLower = term.toLowerCase();
        if (needsWordBoundary(term)) {
            Pattern p = Pattern.compile("(?<![\\w])" + Pattern.quote(termLower) + "(?![\\w])");
            Matcher m = p.matcher(lowerText);
            return m.find() ? m.start() : -1;
        }
        return lowerText.indexOf(termLower);
    }

    /**
     * 术语匹配：短 ASCII 词使用 lookahead/lookbehind 边界（兼容中英文混合），
     * 避免 "SQL" 匹配 "PostgreSQL"、"C" 匹配 "Docker" 等子串误命中。
     * 中文/混合/长词直接 contains 即可。
     */
    private static boolean containsTerm(String lowerText, String term) {
        String termLower = term.toLowerCase();
        if (needsWordBoundary(term)) {
            Pattern p = Pattern.compile("(?<![\\w])" + Pattern.quote(termLower) + "(?![\\w])");
            return p.matcher(lowerText).find();
        }
        return lowerText.contains(termLower);
    }

    /** 所有已知实体类型 */
    public static Set<String> entityTypes() {
        return new LinkedHashSet<>(new LinkedHashSet<>(ENTITY_DICT.values()));
    }

    /** 已知实体数量 */
    public static int dictSize() {
        return ENTITY_DICT.size();
    }
}
