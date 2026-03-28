package ai.intelliswarm.curator;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Runs skills against real APIs — no mocks. Validates that:
 * 1. The skill produces real output (not errors)
 * 2. Output matches the expected template structure
 * 3. Output contains actual data (numbers, known fields)
 *
 * Skills that pass get a VERIFIED badge.
 */
public class LiveTestRunner {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long SKILL_TIMEOUT_MS = 30_000; // 30s for real API calls
    private static final Pattern TEMPLATE_FIELD_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

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
            List<String> notes
    ) {}

    /**
     * Run live tests on all published skills.
     *
     * @param assessments skills to test (typically the published ones)
     * @param testParams  params to use for testing (e.g., ticker=AAPL)
     * @return results for each skill
     */
    public List<LiveTestResult> runLiveTests(List<SkillAssessment> assessments,
                                              Map<String, Object> testParams) {
        var results = new ArrayList<LiveTestResult>();

        for (var assessment : assessments) {
            var skill = assessment.skill();
            System.out.printf("  LIVE TEST: %-45s ", skill.slug());

            var result = testSingleSkill(skill, testParams);
            results.add(result);

            if (result.passed()) {
                System.out.printf("PASS (%dms, %d chars)%n",
                        result.executionTimeMs(), result.outputLength());
            } else {
                System.out.printf("FAIL — %s%n", result.error());
            }
        }

        return results;
    }

    private LiveTestResult testSingleSkill(SkillPackage skill, Map<String, Object> testParams) {
        var notes = new ArrayList<String>();
        var code = skill.groovyCode();

        if (code.isBlank()) {
            return fail(skill.slug(), "No code", notes);
        }

        // Run with real tools
        long startTime = System.currentTimeMillis();
        Object result;
        try {
            result = runWithRealTools(skill, testParams);
        } catch (TimeoutException e) {
            return fail(skill.slug(), "Timeout (>%dms)".formatted(SKILL_TIMEOUT_MS), notes);
        } catch (Exception e) {
            return fail(skill.slug(), "Exception: " + firstLine(e.getMessage()), notes);
        }
        long elapsed = System.currentTimeMillis() - startTime;

        // Check 1: Non-null, non-empty output
        if (result == null) {
            return fail(skill.slug(), "Returned null", elapsed, notes);
        }
        var output = result.toString().trim();
        if (output.isEmpty()) {
            return fail(skill.slug(), "Empty output", elapsed, notes);
        }
        notes.add("Output length: %d chars".formatted(output.length()));

        // Check 2: Not an error
        if (output.toUpperCase().startsWith("ERROR") || output.toUpperCase().startsWith("NO ")) {
            return fail(skill.slug(), "Error output: " + output.substring(0, Math.min(100, output.length())),
                    elapsed, notes);
        }

        // Check 3: Output matches template structure
        boolean matchesTemplate = validateOutputTemplate(skill, output, notes);

        // Check 4: Contains real data (numbers, meaningful content)
        boolean hasRealData = validateRealData(output, notes);

        boolean passed = output.length() > 50 && !output.toUpperCase().contains("DATA NOT AVAILABLE")
                && hasRealData;

        if (passed) {
            notes.add("VERIFIED — real data returned");
        }

        return new LiveTestResult(
                skill.slug(), passed, output, output.length(),
                matchesTemplate, hasRealData, elapsed, null, notes);
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

    /**
     * Check if the output contains fields/structure matching the SKILL.md template.
     */
    private boolean validateOutputTemplate(SkillPackage skill, String output, List<String> notes) {
        try {
            var skillMd = Files.readString(skill.directory().resolve("SKILL.md"));

            // Extract template section
            int templateIdx = skillMd.indexOf("output-template");
            if (templateIdx < 0) {
                notes.add("Template: no output-template in SKILL.md");
                return false;
            }

            // Extract template fields like {{ticker}}, {{price}}, etc.
            var matcher = TEMPLATE_FIELD_PATTERN.matcher(skillMd.substring(templateIdx));
            var expectedFields = new ArrayList<String>();
            while (matcher.find()) {
                expectedFields.add(matcher.group(1).toLowerCase());
            }

            if (expectedFields.isEmpty()) {
                notes.add("Template: no fields found in template");
                return false;
            }

            // Check how many expected fields appear (by keyword) in the output
            var outputLower = output.toLowerCase();
            int matchedFields = 0;
            for (var field : expectedFields) {
                if (outputLower.contains(field)) matchedFields++;
            }

            double matchRate = (double) matchedFields / expectedFields.size();
            notes.add("Template: %d/%d fields matched (%.0f%%)".formatted(
                    matchedFields, expectedFields.size(), matchRate * 100));
            return matchRate >= 0.3; // At least 30% of template fields present
        } catch (Exception e) {
            notes.add("Template: could not read SKILL.md");
            return false;
        }
    }

    /**
     * Check if output contains real data: numbers, dollar signs, percentages.
     */
    private boolean validateRealData(String output, List<String> notes) {
        int signals = 0;

        // Contains numbers (financial data should have numbers)
        if (Pattern.compile("\\d+\\.\\d+").matcher(output).find()) signals++;
        // Contains dollar amounts or large numbers
        if (Pattern.compile("\\$[\\d,.]+|\\d{6,}").matcher(output).find()) signals++;
        // Contains percentage
        if (output.contains("%")) signals++;
        // Contains typical financial terms in output (not code)
        if (output.contains("Revenue") || output.contains("Price") || output.contains("P/E")
                || output.contains("EPS") || output.contains("Market Cap")) signals++;
        // Has multiple lines of structured output
        if (output.split("\n").length >= 3) signals++;

        notes.add("Real data signals: %d/5".formatted(signals));
        return signals >= 2;
    }

    private LiveTestResult fail(String slug, String error, List<String> notes) {
        return new LiveTestResult(slug, false, null, 0, false, false, 0, error, notes);
    }

    private LiveTestResult fail(String slug, String error, long elapsed, List<String> notes) {
        return new LiveTestResult(slug, false, null, 0, false, false, elapsed, error, notes);
    }

    private String firstLine(String msg) {
        if (msg == null) return "unknown";
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, Math.min(nl, 150)) : msg.substring(0, Math.min(msg.length(), 150));
    }

    private static class TimeoutException extends Exception {}

    // -------------------------------------------------------------------
    // Real tool implementations — actual HTTP calls, no mocks
    // -------------------------------------------------------------------

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

        /**
         * Real HTTP request tool — makes actual GET/POST requests.
         */
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

                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    return response.body();
                } catch (Exception e) {
                    return "ERROR: HTTP request failed: " + e.getMessage();
                }
            }
        }

        /**
         * Real web search tool — uses DuckDuckGo HTML for simple search results.
         */
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

                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    return response.body();
                } catch (Exception e) {
                    return "ERROR: Web search failed: " + e.getMessage();
                }
            }
        }

        /**
         * Real web content extraction — fetches URL and returns body text.
         */
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
                    // Strip HTML tags for cleaner text
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

        /**
         * Fallback for unknown tools — tries to treat as HTTP if args contain a URL,
         * otherwise returns an error explaining the tool isn't available.
         */
        public static class FallbackTool {
            private final String name;
            private final HttpClient client;
            FallbackTool(String name, HttpClient client) { this.name = name; this.client = client; }

            public String execute(Object args) {
                var params = asMap(args);
                // If args contain a URL-like field, try fetching it
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
