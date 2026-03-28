package ai.intelliswarm.curator;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilationFailedException;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict skill assessor for publishable quality.
 *
 * 5 dimensions: execution (0-25), effectiveness (0-25),
 * code quality (0-20), test coverage (0-15), uniqueness (0-15).
 *
 * Quality gate: 75/100 minimum for publication.
 */
public class SkillAssessor {

    public static final int PUBLICATION_BAR = 75;

    private static final long EXECUTION_TIMEOUT_MS = 5_000;

    private static final Pattern ASSERTION_PATTERN = Pattern.compile(
            "assert\\s|assertEquals|assertTrue|assertFalse|should\\s|expect\\s");
    private static final Pattern TEST_CASE_PATTERN = Pattern.compile(
            "(?i)(##\\s*Example|##\\s*Test|\\*\\*Input:?\\*\\*|test\\s*case)", Pattern.MULTILINE);
    private static final Pattern ERROR_HANDLING_PATTERN = Pattern.compile(
            "try\\s*\\{|catch\\s*\\(|if\\s*\\(!|if\\s*\\(.*==\\s*null|\\?:\\s|\\?\\.|\\.orElse");
    private static final Pattern PARAM_VALIDATION_PATTERN = Pattern.compile(
            "if\\s*\\(!\\s*\\w+\\)|if\\s*\\(\\s*!\\s*\\w+\\s*\\)|if\\s*\\(\\s*\\w+\\s*==\\s*null");
    private static final Pattern STRUCTURED_OUTPUT_PATTERN = Pattern.compile(
            "output\\s*<<|output\\s*\\+=|output\\.add|join\\(|String\\.format|\"\\$\\{");
    private static final Pattern HARDCODED_URL_PATTERN = Pattern.compile(
            "https?://[^\"'\\s]+", Pattern.CASE_INSENSITIVE);

    // ---- EXECUTION (0-25) ----

    public ExecutionResult assessExecution(SkillPackage skill) {
        var notes = new ArrayList<String>();
        int score = 0;

        if (skill.groovyCode().isBlank()) {
            notes.add("Execution: FAIL — no Groovy code found");
            return new ExecutionResult(0, notes);
        }

        // 1. Compilation check (8 pts)
        var shell = new GroovyShell();
        try {
            shell.parse(skill.groovyCode());
            score += 8;
            notes.add("Execution: Compilation OK");
        } catch (CompilationFailedException e) {
            notes.add("Execution: FAIL — compilation error: " + firstLine(e.getMessage()));
            return new ExecutionResult(0, notes);
        }

        // 2. Sandbox execution with valid params (9 pts)
        Object result = null;
        try {
            result = runSandbox(skill, buildMockParams(skill));
            if (result != null) {
                score += 9;
                notes.add("Execution: Sandbox run OK");
            } else {
                score += 3;
                notes.add("Execution: Sandbox returned null");
            }
        } catch (TimeoutException e) {
            score += 2;
            notes.add("Execution: TIMEOUT (>%dms)".formatted(EXECUTION_TIMEOUT_MS));
        } catch (Exception e) {
            score += 3;
            notes.add("Execution: Sandbox exception — " + firstLine(e.getMessage()));
        }

        // 3. Output quality check (4 pts)
        if (result != null) {
            var output = result.toString();
            if (output.length() > 20) score += 2;
            if (!output.toUpperCase().startsWith("ERROR")) score += 2;
            else notes.add("Execution: Output starts with ERROR — likely missing data");
        }

        // 4. Error-path test — missing params should return graceful error, not crash (4 pts)
        try {
            var errorResult = runSandbox(skill, Map.of()); // empty params
            if (errorResult != null && errorResult.toString().toUpperCase().contains("ERROR")) {
                score += 4;
                notes.add("Execution: Error path handled gracefully");
            } else if (errorResult != null) {
                score += 2;
                notes.add("Execution: Error path returned result (no param validation)");
            }
        } catch (Exception e) {
            notes.add("Execution: Error path crashed — missing param validation");
        }

        return new ExecutionResult(Math.min(25, score), notes);
    }

    private Object runSandbox(SkillPackage skill, Map<String, Object> params) throws Exception {
        var binding = new Binding();
        binding.setVariable("params", params);
        binding.setVariable("tools", new MockToolProxy());
        var sandboxShell = new GroovyShell(binding);

        var resultHolder = new Object[1];
        var errorHolder = new Exception[1];

        var thread = Thread.ofVirtual().start(() -> {
            try {
                resultHolder[0] = sandboxShell.evaluate(skill.groovyCode());
            } catch (Exception e) {
                errorHolder[0] = e;
            }
        });
        thread.join(EXECUTION_TIMEOUT_MS);

        if (thread.isAlive()) {
            thread.interrupt();
            throw new TimeoutException();
        }
        if (errorHolder[0] != null) throw errorHolder[0];
        return resultHolder[0];
    }

    // ---- EFFECTIVENESS (0-25) ----

    public int assessEffectiveness(SkillPackage skill, List<String> notes) {
        if (skill.usageCount() == 0) {
            notes.add("Effectiveness: 0 usage — untested in production");
            return 0;
        }

        double rate = skill.successRate();
        int score;

        // Strict: low success rate is heavily penalized
        if (rate >= 0.90) score = 20;
        else if (rate >= 0.75) score = 15;
        else if (rate >= 0.50) score = 8;
        else score = 3; // < 50% success = nearly failing

        // Volume bonus (only if success rate is decent)
        if (rate >= 0.70) {
            if (skill.usageCount() >= 20) score = Math.min(25, score + 5);
            else if (skill.usageCount() >= 10) score = Math.min(25, score + 3);
            else if (skill.usageCount() >= 5) score = Math.min(25, score + 1);
        }

        notes.add("Effectiveness: %d uses, %.0f%% success → %d/25".formatted(
                skill.usageCount(), rate * 100, score));
        return score;
    }

    // ---- CODE QUALITY (0-20) ----

    public int assessCodeQuality(SkillPackage skill, List<String> notes) {
        var code = skill.groovyCode();
        int score = 0;
        var findings = new ArrayList<String>();

        // 1. From existing quality score (0-8)
        int fromExisting = (int) Math.round(skill.qualityScore().total() / 100.0 * 8);
        score += fromExisting;

        // 2. Parameter validation (0-3)
        if (PARAM_VALIDATION_PATTERN.matcher(code).find()) {
            score += 3;
        } else {
            findings.add("no param validation");
        }

        // 3. Error handling (0-3)
        if (ERROR_HANDLING_PATTERN.matcher(code).find()) {
            score += 3;
        } else {
            findings.add("no error handling");
        }

        // 4. Structured output format (0-2)
        if (STRUCTURED_OUTPUT_PATTERN.matcher(code).find() || code.contains("return ")) {
            score += 2;
        } else {
            findings.add("no structured output");
        }

        // 5. Code size — not too long, not trivial (0-2)
        var lines = code.split("\n").length;
        if (lines >= 10 && lines <= 80) score += 2;
        else if (lines >= 5 && lines <= 120) score += 1;
        else findings.add(lines < 5 ? "trivially short" : "excessively long (%d lines)".formatted(lines));

        // 6. No hardcoded secrets/keys (0-2) — penalty if found
        if (!code.contains("api_key") && !code.contains("apiKey") && !code.contains("secret")) {
            score += 2;
        } else {
            findings.add("WARN: possible hardcoded credentials");
        }

        score = Math.min(20, score);
        var findingsStr = findings.isEmpty() ? "clean" : String.join(", ", findings);
        notes.add("Code quality: existing=%d/100, checks=[%s] → %d/20".formatted(
                skill.qualityScore().total(), findingsStr, score));
        return score;
    }

    // ---- TEST COVERAGE (0-15) ----

    public int assessTestCoverage(SkillPackage skill, List<String> notes) {
        int score = 0;
        try {
            var skillMd = java.nio.file.Files.readString(skill.directory().resolve("SKILL.md"));

            // Count test/example sections
            var testMatcher = TEST_CASE_PATTERN.matcher(skillMd);
            int testCount = 0;
            while (testMatcher.find()) testCount++;

            // Strict: need at least 2 examples for any meaningful score
            if (testCount >= 3) score += 7;
            else if (testCount >= 2) score += 5;
            else if (testCount >= 1) score += 2;
            else notes.add("Test coverage: WARN — no examples found");

            // Assertions present?
            if (ASSERTION_PATTERN.matcher(skillMd).find()) score += 4;

            // Has expected output template?
            if (skillMd.contains("Expected Output") || skillMd.contains("expected output")
                    || skillMd.contains("output-template")) score += 4;

            score = Math.min(15, score);
            notes.add("Test coverage: %d examples, assertions=%s → %d/15".formatted(
                    testCount,
                    ASSERTION_PATTERN.matcher(skillMd).find() ? "yes" : "no",
                    score));
        } catch (Exception e) {
            notes.add("Test coverage: Could not read SKILL.md — 0/15");
        }
        return score;
    }

    // ---- HELPERS ----

    private Map<String, Object> buildMockParams(SkillPackage skill) {
        var params = new HashMap<String, Object>();
        if (skill.tags().stream().anyMatch(t -> t.contains("finance") || t.contains("stock"))) {
            params.put("ticker", "AAPL");
            params.put("symbol", "AAPL");
        }
        params.put("query", "test query");
        params.put("windowDays", 7);
        return params;
    }

    private String firstLine(String msg) {
        if (msg == null) return "unknown";
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, Math.min(nl, 120)) : msg.substring(0, Math.min(msg.length(), 120));
    }

    public record ExecutionResult(int score, List<String> notes) {}

    private static class TimeoutException extends Exception {}

    /**
     * Groovy-accessible mock that returns realistic placeholder data for any tool call.
     */
    public static class MockToolProxy extends groovy.util.Proxy {
        @Override
        public Object getProperty(String name) {
            return new MockTool(name);
        }

        public static class MockTool {
            private final String name;
            MockTool(String name) { this.name = name; }
            public String execute(Object args) {
                return "{\"ticker\": \"AAPL\", \"price\": 185.50, \"pe_ratio\": 28.5, " +
                       "\"revenue\": 394328000000, \"net_income\": 96995000000, " +
                       "\"eps\": 6.14, \"market_cap\": 2890000000000, " +
                       "\"tool\": \"%s\", \"status\": \"ok\"}".formatted(name);
            }
        }
    }
}
