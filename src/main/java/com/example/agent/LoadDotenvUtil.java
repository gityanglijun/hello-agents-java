package com.example.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class LoadDotenvUtil {

    /** 依次尝试多个文件，后面的覆盖前面的（.env 优先级最高） */
    public static Map<String, String> loadEnvFile() {
        Map<String, String> envMap = new HashMap<>();

        // 优先级从低到高：.env.example → .env
        loadFile(Path.of(".env.example"), envMap);
        loadFile(Path.of(".env"), envMap);

        return envMap;
    }

    private static void loadFile(Path filePath, Map<String, String> envMap) {
        if (!Files.exists(filePath)) {
            System.out.println("[dotenv] 跳过: " + filePath.getFileName() + " (不存在)");
            return;
        }
        try {
            int count = 0;
            for (String line : Files.readAllLines(filePath)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eqIdx = line.indexOf('=');
                if (eqIdx == -1) continue;
                String key = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                envMap.put(key, value);
                count++;
            }
            System.out.println("[dotenv] 加载: " + filePath.getFileName() + " (" + count + " 项)");
        } catch (IOException e) {
            System.err.println("[dotenv] 读取失败: " + filePath + " - " + e.getMessage());
        }
    }
}
