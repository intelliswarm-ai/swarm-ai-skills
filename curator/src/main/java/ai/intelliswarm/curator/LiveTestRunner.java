package ai.intelliswarm.curator;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic live test runner — inspects each skill to discover what parameters
 * it needs, builds test cases from SKILL.md examples and code analysis,
 * then runs against real APIs with no mocks.
 */
public class LiveTestRunner {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long SKILL_TIMEOUT_MS = 30_000;
    private static final Pattern TEMPLATE_FIELD_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    // Extract params.get("X") or params.get('X') or params.X from code
    private static final Pattern PARAM_GET_PATTERN = Pattern.compile(
            "params\\.get\\([\"']([^\"']+)[\"']\\)|params\\.(\\w+)");

    // Extract example inputs from SKILL.md: params = [key: "value"] or **Input:** params = [...]
    private static final Pattern EXAMPLE_INPUT_PATTERN = Pattern.compile(
            "(?:params\\s*=\\s*\\[([^\\]]+)]|\\*\\*Input:?\\*\\*.*?\\[([^\\]]+)])", Pattern.DOTALL);

    // Extract key: "value" or key: 'value' or key: number from example maps
    private static final Pattern MAP_ENTRY_PATTERN = Pattern.compile(
            "(\\w+):\\s*[\"']([^\"']*)[\"']|(\\w+):\\s*(\\d+\\.?\\d*)");

    // Known param types and sample values for auto-generation
    private static final Map<String, List<Object>> KNOWN_PARAM_VALUES = Map.ofEntries(
            // Finance
            Map.entry("ticker", List.of("AAPL", "MSFT", "GOOGL")),
            Map.entry("symbol", List.of("AAPL", "MSFT", "GOOGL")),
            Map.entry("company", List.of("Apple", "Microsoft", "Google")),
            Map.entry("stock", List.of("AAPL", "TSLA")),
            // General
            Map.entry("query", List.of("latest technology news", "weather forecast")),
            Map.entry("url", List.of("https://httpbin.org/json")),
            Map.entry("text", List.of("Hello world, this is a test input.")),
            Map.entry("input", List.of("sample input data")),
            Map.entry("name", List.of("test-skill")),
            Map.entry("topic", List.of("artificial intelligence")),
            Map.entry("keyword", List.of("machine learning")),
            Map.entry("search", List.of("OpenAI GPT")),
            // Numeric
            Map.entry("windowDays", List.of(7, 30)),
            Map.entry("days", List.of(7, 14)),
            Map.entry("limit", List.of(10, 5)),
            Map.entry("count", List.of(10)),
            Map.entry("numResults", List.of(5)),
            Map.entry("timeout", List.of(10))
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record LiveTestResult(
            String slug,
            boolean passed,
            String output,
            int outputLength,
            boolean outputMatchesTemplate,
            boolean containsRealData,
            long executionTimeMs,
            String error,
            List<String> notes,
            Map<String, Object> testParams
    ) {}

    /**
     * Run live tests on all published skills. Each skill is tested with
     * dynamically discovered parameters.
     */
    public List<LiveTestResult> runLiveTests(List<SkillAssessment> assessments,
                                              Map<String, Object> globalParams) {
        var results = new ArrayList<LiveTestResult>();

        for (var assessment : assessments) {
            var skill = assessment.skill();

            // Discover test cases for this specific skill
            var testCases = discoverTestCases(skill, globalParams);

            System.out.printf("  LIVE TEST: %-40s [%d test case(s)]%n",
                    skill.slug(), testCases.size());

            // Run each test case — skill passes if ANY test case succeeds
            LiveTestResult bestResult = null;
            for (int i = 0; i < testCases.size(); i++) {
                var testParams = testCases.get(i);
                System.out.printf("    Case %d: %s ... ", i + 1, summarizeParams(testParams));

                var result = testSingleSkill(skill, testParams);

                if (result.passed()) {
                    System.out.printf("PASS (%dms, %d chars)%n",
                            result.executionTimeMs(), result.outputLength());
                    bestResult = result;
                    break; // first pass is enough
                } else {
                    System.out.printf("FAIL — %s%n",
                            result.error() != null ? result.error() : "no real data");
                }

                // Keep the best failed result (most output)
                if (bestResult == null || (result.outputLength() > bestResult.outputLength())) {
                    bestResult = result;
                }
            }

            results.add(bestResult != null ? bestResult
                    : fail(skill.slug(), "No test cases generated", new ArrayList<>(), Map.of()));
        }

        return results;
    }

    // ---- TEST CASE DISCOVERY ----

    /**
     * Build test cases for a skill by:
     * 1. Parsing examples from SKILL.md
     * 2. Extracting param names from code
     * 3. Generating params from known value mappings
     */
    private List<Map<String, Object>> discoverTestCases(SkillPackage skill,
                                                         Map<String, Object> globalParams) {
        var testCases = new ArrayList<Map<String, Object>>();

        // 1. Extract examples from SKILL.md
        var exampleCases = extractExamplesFromSkillMd(skill);
        testCases.addAll(exampleCases);

        // 2. Build a case from code analysis + global params
        var codeParams = extractParamsFromCode(skill);
        if (!codeParams.isEmpty()) {
            var generated = new LinkedHashMap<String, Object>();
            for (var paramName : codeParams) {
                // Priority: global params > known values > generic fallback
                if (globalParams.containsKey(paramName)) {
                    generated.put(paramName, globalParams.get(paramName));
                } else if (KNOWN_PARAM_VALUES.containsKey(paramName)) {
                    generated.put(paramName, KNOWN_PARAM_VALUES.get(paramName).getFirst());
                } else {
                    generated.put(paramName, "test_value");
                }
            }
            if (!generated.isEmpty() && !testCases.contains(generated)) {
                testCases.add(generated);
            }
        }

        // 3. If we still have nothing, use global params as fallback
        if (testCases.isEmpty() && !globalParams.isEmpty()) {
            testCases.add(new LinkedHashMap<>(globalParams));
        }

        // 4. Generate a second test case with alternate values for robustness
        if (!codeParams.isEmpty() && testCases.size() < 2) {
            var alternate = new LinkedHashMap<String, Object>();
            for (var paramName : codeParams) {
                var known = KNOWN_PARAM_VALUES.get(paramName);
                if (known != null && known.size() > 1) {
                    alternate.put(paramName, known.get(1)); // second value
                } else if (globalParams.containsKey(paramName)) {
                    alternate.put(paramName, globalParams.get(paramName));
                } else {
                    alternate.put(paramName, "alternate_test");
                }
            }
            if (!alternate.isEmpty() && !testCases.contains(alternate)) {
                testCases.add(alternate);
            }
        }

        return testCases;
    }

    /**
     * Parse SKILL.md for example inputs like:
     *   **Input:** params = [ticker: "AAPL"]
     */
    private List<Map<String, Object>> extractExamplesFromSkillMd(SkillPackage skill) {
        var cases = new ArrayList<Map<String, Object>>();
        try {
            var md = Files.readString(skill.directory().resolve("SKILL.md"));
            var matcher = EXAMPLE_INPUT_PATTERN.matcher(md);
            while (matcher.find()) {
                var mapStr = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (mapStr == null) continue;

                var params = parseMapLiteral(mapStr);
                if (!params.isEmpty()) {
                    cases.add(params);
                }
            }
        } catch (Exception ignored) {}
        return cases;
    }

    /**
     * Parse key: "value" entries from a Groovy map literal string.
     */
    private Map<String, Object> parseMapLiteral(String mapStr) {
        var params = new LinkedHashMap<String, Object>();
        var matcher = MAP_ENTRY_PATTERN.matcher(mapStr);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                params.put(matcher.group(1), matcher.group(2));
            } else if (matcher.group(3) != null) {
                try {
                    params.put(matcher.group(3), Double.parseDouble(matcher.group(4)));
                } catch (NumberFormatException e) {
                    params.put(matcher.group(3), matcher.group(4));
                }
            }
        }
        return params;
    }

    /**
     * Extract parameter names from Groovy code: params.get("X"), params.X
     */
    private List<String> extractParamsFromCode(SkillPackage skill) {
        var paramNames = new LinkedHashSet<String>();
        var matcher = PARAM_GET_PATTERN.matcher(skill.groovyCode());
        while (matcher.find()) {
            var name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (name != null && !name.equals("get") && !name.equals("containsKey")) {
                paramNames.add(name);
            }
        }
        return new ArrayList<>(paramNames);
    }

    private String summarizeParams(Map<String, Object> params) {
        var sb = new StringBuilder("{");
        int i = 0;
        for (var entry : params.entrySet()) {
            if (i++ > 0) sb.append(", ");
            var val = entry.getValue().toString();
            sb.append(entry.getKey()).append("=")
              .append(val.length() > 20 ? val.substring(0, 20) + "..." : val);
            if (i >= 3) { sb.append(", ..."); break; }
        }
        sb.append("}");
        return sb.toString();
    }

    // ---- SKILL EXECUTION ----

    private LiveTestResult testSingleSkill(SkillPackage skill, Map<String, Object> testParams) {
        var notes = new ArrayList<String>();
        var code = skill.groovyCode();

        if (code.isBlank()) {
            return fail(skill.slug(), "No code", notes, testParams);
        }

        notes.add("Test params: " + summarizeParams(testParams));

        long startTime = System.currentTimeMillis();
        Object result;
        try {
            result = runWithRealTools(skill, testParams);
        } catch (TimeoutException e) {
            return fail(skill.slug(), "Timeout (>%dms)".formatted(SKILL_TIMEOUT_MS), notes, testParams);
        } catch (Exception e) {
            return fail(skill.slug(), "Exception: " + firstLine(e.getMessage()), notes, testParams);
        }
        long elapsed = System.currentTimeMillis() - startTime;

        if (result == null) {
            return fail(skill.slug(), "Returned null", elapsed, notes, testParams);
        }
        var output = result.toString().trim();
        if (output.isEmpty()) {
            return fail(skill.slug(), "Empty output", elapsed, notes, testParams);
        }
        notes.add("Output length: %d chars".formatted(output.length()));

        if (output.toUpperCase().startsWith("ERROR") || output.toUpperCase().startsWith("NO ")) {
            return fail(skill.slug(),
                    "Error output: " + output.substring(0, Math.min(100, output.length())),
                    elapsed, notes, testParams);
        }

        boolean matchesTemplate = validateOutputTemplate(skill, output, notes);
        boolean hasRealData = validateRealData(output, notes);

        boolean passed = output.length() > 50
                && !output.toUpperCase().contains("DATA NOT AVAILABLE")
                && hasRealData;

        if (passed) {
            notes.add("VERIFIED — real data returned");
        }

        return new LiveTestResult(
                skill.slug(), passed, output, output.length(),
                matchesTemplate, hasRealData, elapsed, null, notes, testParams);
    }

    private Object runWithRealTools(SkillPackage skill, Map<String, Object> params) throws Exception {
        var binding = new Binding();
        binding.setVariable("params", new HashMap<>(params));
        binding.setVariable("tools", new RealToolProxy(httpClient));

        var shell = new GroovyShell(binding);
        var resultHolder = new Object[1];
        var errorHolder = new Exception[1];

        var thread = Thread.ofVirtual().start(() -> {
            try {
                resultHolder[0] = shell.evaluate(skill.groovyCode());
            } catch (Exception e) {
                errorHolder[0] = e;
            }
        });
        thread.join(SKILL_TIMEOUT_MS);

        if (thread.isAlive()) {
            thread.interrupt();
            throw new TimeoutException();
        }
        if (errorHolder[0] != null) throw errorHolder[0];
        return resultHolder[0];
    }

    // ---- OUTPUT VALIDATION ----

    private boolean validateOutputTemplate(SkillPackage skill, String output, List<String> notes) {
        try {
            var skillMd = Files.readString(skill.directory().resolve("SKILL.md"));
            int templateIdx = skillMd.indexOf("output-template");
            if (templateIdx < 0) {
                notes.add("Template: no output-template in SKILL.md");
                return false;
            }

            var matcher = TEMPLATE_FIELD_PATTERN.matcher(skillMd.substring(templateIdx));
            var expectedFields = new ArrayList<String>();
            while (matcher.find()) {
                expectedFields.add(matcher.group(1).toLowerCase());
            }

            if (expectedFields.isEmpty()) {
                notes.add("Template: no fields found in template");
                return false;
            }

            var outputLower = output.toLowerCase();
            int matchedFields = 0;
            for (var field : expectedFields) {
                if (outputLower.contains(field)) matchedFields++;
            }

            double matchRate = (double) matchedFields / expectedFields.size();
            notes.add("Template: %d/%d fields matched (%.0f%%)".formatted(
                    matchedFields, expectedFields.size(), matchRate * 100));
            return matchRate >= 0.3;
        } catch (Exception e) {
            notes.add("Template: could not read SKILL.md");
            return false;
        }
    }

    private boolean validateRealData(String output, List<String> notes) {
        int signals = 0;

        if (Pattern.compile("\\d+\\.\\d+").matcher(output).find()) signals++;
        if (Pattern.compile("\\$[\\d,.]+|\\d{6,}").matcher(output).find()) signals++;
        if (output.contains("%")) signals++;
        if (output.contains("Revenue") || output.contains("Price") || output.contains("P/E")
                || output.contains("EPS") || output.contains("Market Cap")
                || output.contains("result") || output.contains("data")
                || output.contains("status") || output.contains("response")) signals++;
        if (output.split("\n").length >= 3) signals++;

        notes.add("Real data signals: %d/5".formatted(signals));
        return signals >= 2;
    }

    // ---- HELPERS ----

    private LiveTestResult fail(String slug, String error, List<String> notes,
                                 Map<String, Object> params) {
        return new LiveTestResult(slug, false, null, 0, false, false, 0, error, notes, params);
    }

    private LiveTestResult fail(String slug, String error, long elapsed,
                                 List<String> notes, Map<String, Object> params) {
        return new LiveTestResult(slug, false, null, 0, false, false, elapsed, error, notes, params);
    }

    private String firstLine(String msg) {
        if (msg == null) return "unknown";
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, Math.min(nl, 150)) : msg.substring(0, Math.min(msg.length(), 150));
    }

    private static class TimeoutException extends Exception {}

    // ---- REAL TOOL IMPLEMENTATIONS ----

    public static class RealToolProxy extends groovy.util.Proxy {
        private final HttpClient httpClient;

        public RealToolProxy(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public Object getProperty(String name) {
            return switch (name) {
                case "http_request" -> new HttpRequestTool(httpClient);
                case "web_search" -> new WebSearchTool(httpClient);
                case "web_content_extract" -> new WebContentExtractTool(httpClient);
                default -> new FallbackTool(name, httpClient);
            };
        }

        public static class HttpRequestTool {
            private final HttpClient client;
            HttpRequestTool(HttpClient client) { this.client = client; }

            public String execute(Object args) {
                try {
                    var params = asMap(args);
                    var url = params.getOrDefault("url", "").toString();
                    if (url.isBlank()) return "ERROR: No URL provided";

                    var method = params.getOrDefault("method", "GET").toString().toUpperCase();
                    var builder = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(HTTP_TIMEOUT)
                            .header("User-Agent", "SkillCurator/1.0")
                            .header("Accept", "application/json, text/html, */*");

                    var request = "POST".equals(method)
                            ? builder.POST(HttpRequest.BodyPublishers.ofString(
                                    params.getOrDefault("body", "").toString())).build()
                            : builder.GET().build();

                    return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
                } catch (Exception e) {
                    return "ERROR: HTTP request failed: " + e.getMessage();
                }
            }
        }

        public static class WebSearchTool {
            private final HttpClient client;
            WebSearchTool(HttpClient client) { this.client = client; }

            public String execute(Object args) {
                try {
                    var params = asMap(args);
                    var query = params.getOrDefault("query", "").toString();
                    if (query.isBlank()) return "ERROR: No query provided";

                    var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                    var url = "https://html.duckduckgo.com/html/?q=" + encoded;

                    var request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(HTTP_TIMEOUT)
                            .header("User-Agent", "SkillCurator/1.0")
                            .GET().build();

                    return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
                } catch (Exception e) {
                    return "ERROR: Web search failed: " + e.getMessage();
                }
            }
        }

        public static class WebContentExtractTool {
            private final HttpClient client;
            WebContentExtractTool(HttpClient client) { this.client = client; }

            public String execute(Object args) {
                try {
                    var params = asMap(args);
                    var url = params.getOrDefault("url", "").toString();
                    if (url.isBlank()) return "ERROR: No URL provided";

                    var request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(HTTP_TIMEOUT)
                            .header("User-Agent", "SkillCurator/1.0")
                            .GET().build();

                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    var body = response.body()
                            .replaceAll("<script[^>]*>.*?</script>", " ")
                            .replaceAll("<style[^>]*>.*?</style>", " ")
                            .replaceAll("<[^>]+>", " ")
                            .replaceAll("\\s+", " ")
                            .trim();
                    return body.substring(0, Math.min(body.length(), 10000));
                } catch (Exception e) {
                    return "ERROR: Content extraction failed: " + e.getMessage();
                }
            }
        }

        public static class FallbackTool {
            private final String name;
            private final HttpClient client;
            FallbackTool(String name, HttpClient client) { this.name = name; this.client = client; }

            public String execute(Object args) {
                var params = asMap(args);
                for (var value : params.values()) {
                    var val = value.toString();
                    if (val.startsWith("http://") || val.startsWith("https://")) {
                        try {
                            var request = HttpRequest.newBuilder()
                                    .uri(URI.create(val))
                                    .timeout(HTTP_TIMEOUT)
                                    .header("User-Agent", "SkillCurator/1.0")
                                    .GET().build();
                            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
                        } catch (Exception e) {
                            return "ERROR: Tool '%s' fetch failed: %s".formatted(name, e.getMessage());
                        }
                    }
                }
                return "ERROR: Tool '%s' is not available in live test mode".formatted(name);
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object args) {
            if (args instanceof Map) return (Map<String, Object>) args;
            return Map.of();
        }
    }
}
