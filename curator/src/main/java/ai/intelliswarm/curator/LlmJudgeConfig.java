package ai.intelliswarm.curator;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads LLM judge configuration from curator.yaml + .env file.
 *
 * Resolution order for API key:
 *   1. .env file in same directory as curator.yaml
 *   2. Environment variable (ANTHROPIC_API_KEY or OPENAI_API_KEY)
 */
public class LlmJudgeConfig {

    private static final String DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com";
    private static final String DEFAULT_OPENAI_BASE = "https://api.openai.com";

    private final String provider;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final String baseUrl;
    private final String apiKey;

    private LlmJudgeConfig(String provider, String model, int maxTokens,
                            double temperature, String baseUrl, String apiKey) {
        this.provider = provider;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Load config from a curator.yaml file + companion .env.
     */
    @SuppressWarnings("unchecked")
    public static LlmJudgeConfig load(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            throw new IOException("Config file not found: " + configFile);
        }

        var yaml = new Yaml();
        Map<String, Object> root;
        try (var reader = Files.newBufferedReader(configFile)) {
            root = yaml.load(reader);
        }

        var llm = (Map<String, Object>) root.getOrDefault("llm", Map.of());

        var provider = getString(llm, "provider", "anthropic");
        var model = getString(llm, "model", "claude-sonnet-4-20250514");
        var maxTokens = getInt(llm, "maxTokens", 4096);
        var temperature = getDouble(llm, "temperature", 0.2);
        var baseUrl = getString(llm, "baseUrl", "");

        if (baseUrl.isBlank()) {
            baseUrl = provider.equals("openai") ? DEFAULT_OPENAI_BASE : DEFAULT_ANTHROPIC_BASE;
        }

        // Resolve API key from .env then environment
        var envFile = configFile.getParent().resolve(".env");
        var envVars = loadEnvFile(envFile);

        String apiKey;
        if (provider.equals("openai")) {
            apiKey = resolveKey(envVars, "OPENAI_API_KEY");
        } else {
            apiKey = resolveKey(envVars, "ANTHROPIC_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            var keyName = provider.equals("openai") ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY";
            throw new IOException(
                    "No API key found. Set %s in %s or as an environment variable."
                            .formatted(keyName, envFile));
        }

        return new LlmJudgeConfig(provider, model, maxTokens, temperature, baseUrl, apiKey);
    }

    private static String resolveKey(Map<String, String> envVars, String keyName) {
        // 1. .env file
        var fromFile = envVars.get(keyName);
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        // 2. System environment
        var fromEnv = System.getenv(keyName);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return null;
    }

    private static Map<String, String> loadEnvFile(Path envFile) {
        var map = new java.util.HashMap<String, String>();
        if (!Files.exists(envFile)) return map;
        try {
            for (var line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var eq = line.indexOf('=');
                if (eq > 0) {
                    var key = line.substring(0, eq).trim();
                    var value = line.substring(eq + 1).trim();
                    // Strip surrounding quotes
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    map.put(key, value);
                }
            }
        } catch (IOException ignored) {}
        return map;
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        var val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        var val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        var val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val != null) {
            try { return Double.parseDouble(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    // ---- Getters ----

    public String provider()    { return provider; }
    public String model()       { return model; }
    public int maxTokens()      { return maxTokens; }
    public double temperature() { return temperature; }
    public String baseUrl()     { return baseUrl; }
    public String apiKey()      { return apiKey; }

    @Override
    public String toString() {
        return "LlmJudgeConfig{provider=%s, model=%s, maxTokens=%d, temperature=%.1f, baseUrl=%s}"
                .formatted(provider, model, maxTokens, temperature, baseUrl);
    }
}
