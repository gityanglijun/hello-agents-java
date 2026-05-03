package com.example.agent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoadDotenvUtil {

    public static Map<String,String> loadEnvFile() {
        Map<String,String> envMap = new HashMap<>();
        List<String> searchPaths = List.of(
                ".env",
                "src/main/java/.env",
                "src/.env"
        );
        for (String path : searchPaths) {
            Path envPath = Paths.get(path);
            if (Files.exists(envPath)) {
                try {
                    List<String> lines = Files.readAllLines(envPath);
                    for (String line : lines) {
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
                    }
                } catch (IOException ignored) {}
                break;
            }
        }
        return envMap;
    }

}
