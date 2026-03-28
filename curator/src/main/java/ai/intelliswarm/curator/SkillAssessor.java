package ai.intelliswarm.curator;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilationFailedException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict skill assessor for publishable quality.
 *
 * 7 dimensions (100 total):
 *   Execution (0-20), Effectiveness (0-15), Code Quality (0-15),
 *   Test Coverage (0-10), Uniqueness (0-10),
 *   Self-Containment (0-15), Universality (0-15).
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

    // Self-containment: detect calls to other skills via tools.*
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "tools\\.(\\w+)");

    // ---- EXECUTION (0-20) ----

    public ExecutionResult assessExecution(SkillPackage skill) {
        var notes = new ArrayList<String>();
        int score = 0;

        if (skill.groovyCode().isBlank()) {
            notes.add("Execution: FAIL — no Groovy code found");
            return new ExecutionResult(0, notes);
        }

        // 1. Compilation check (6 pts)
        var shell = new GroovyShell();
        try {
            shell.parse(skill.groovyCode());
            score += 6;
            notes.add("Execution: Compilation OK");
        } catch (CompilationFailedException e) {
            notes.add("Execution: FAIL — compilation error: " + firstLine(e.getMessage()));
            return new ExecutionResult(0, notes);
        }

        // 2. Sandbox execution with valid params (7 pts)
        Object result = null;
        try {
            result = runSandbox(skill, buildMockParams(skill));
            if (result != null) {
                score += 7;
                notes.add("Execution: Sandbox run OK");
            } else {
                score += 2;
                notes.add("Execution: Sandbox returned null");
            }
        } catch (TimeoutException e) {
            score += 1;
            notes.add("Execution: TIMEOUT (>%dms)".formatted(EXECUTION_TIMEOUT_MS));
        } catch (Exception e) {
            score += 2;
            notes.add("Execution: Sandbox exception — " + firstLine(e.getMessage()));
        }

        // 3. Output quality check (4 pts)
        if (result != null) {
            var output = result.toString();
            if (output.length() > 20) score += 2;
            if (!output.toUpperCase().startsWith("ERROR")) score += 2;
            else notes.add("Execution: Output starts with ERROR");
        }

        // 4. Error-path test (3 pts)
        try {
            var errorResult = runSandbox(skill, Map.of());
            if (errorResult != null && errorResult.toString().toUpperCase().contains("ERROR")) {
                score += 3;
                notes.add("Execution: Error path handled gracefully");
            } else if (errorResult != null) {
                score += 1;
                notes.add("Execution: Error path — no param validation");
            }
        } catch (Exception e) {
            notes.add("Execution: Error path crashed — missing param validation");
        }

        return new ExecutionResult(Math.min(20, score), notes);
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

    // ---- EFFECTIVENESS (0-15) ----

    public int assessEffectiveness(SkillPackage skill, List<String> notes) {
        if (skill.usageCount() == 0) {
            notes.add("Effectiveness: 0 usage — untested in production");
            return 0;
        }

        double rate = skill.successRate();
        int score;

        if (rate >= 0.90) score = 12;
        else if (rate >= 0.75) score = 9;
        else if (rate >= 0.50) score = 5;
        else score = 2;

        // Volume bonus (only if success rate >= 70%)
        if (rate >= 0.70) {
            if (skill.usageCount() >= 20) score = Math.min(15, score + 3);
            else if (skill.usageCount() >= 10) score = Math.min(15, score + 2);
            else if (skill.usageCount() >= 5) score = Math.min(15, score + 1);
        }

        notes.add("Effectiveness: %d uses, %.0f%% success → %d/15".formatted(
                skill.usageCount(), rate * 100, score));
        return score;
    }

    // ---- CODE QUALITY (0-15) ----

    public int assessCodeQuality(SkillPackage skill, List<String> notes) {
        var code = skill.groovyCode();
        int score = 0;
        var findings = new ArrayList<String>();

        // 1. From existing quality score (0-6)
        int fromExisting = (int) Math.round(skill.qualityScore().total() / 100.0 * 6);
        score += fromExisting;

        // 2. Parameter validation (0-2)
        if (PARAM_VALIDATION_PATTERN.matcher(code).find()) {
            score += 2;
        } else {
            findings.add("no param validation");
        }

        // 3. Error handling (0-2)
        if (ERROR_HANDLING_PATTERN.matcher(code).find()) {
            score += 2;
        } else {
            findings.add("no error handling");
        }

        // 4. Structured output (0-2)
        if (STRUCTURED_OUTPUT_PATTERN.matcher(code).find() || code.contains("return ")) {
            score += 2;
        } else {
            findings.add("no structured output");
        }

        // 5. Code substance — more code = more logic = likely more useful (0-2)
        var lines = code.split("\n").length;
        if (lines >= 40) score += 2;
        else if (lines >= 15) score += 1;
        else findings.add("trivially short (%d lines)".formatted(lines));

        // 6. No hardcoded secrets (0-2)
        if (!code.contains("api_key") && !code.contains("apiKey") && !code.contains("secret")) {
            score += 2;
        } else {
            findings.add("WARN: possible hardcoded credentials");
        }

        score = Math.min(15, score);
        var findingsStr = findings.isEmpty() ? "clean" : String.join(", ", findings);
        notes.add("Code quality: existing=%d/100, checks=[%s] → %d/15".formatted(
                skill.qualityScore().total(), findingsStr, score));
        return score;
    }

    // ---- TEST COVERAGE (0-10) ----

    public int assessTestCoverage(SkillPackage skill, List<String> notes) {
        int score = 0;
        try {
            var skillMd = java.nio.file.Files.readString(skill.directory().resolve("SKILL.md"));

            var testMatcher = TEST_CASE_PATTERN.matcher(skillMd);
            int testCount = 0;
            while (testMatcher.find()) testCount++;

            if (testCount >= 3) score += 4;
            else if (testCount >= 2) score += 3;
            else if (testCount >= 1) score += 1;
            else notes.add("Test coverage: WARN — no examples found");

            if (ASSERTION_PATTERN.matcher(skillMd).find()) score += 3;

            if (skillMd.contains("Expected Output") || skillMd.contains("expected output")
                    || skillMd.contains("output-template")) score += 3;

            score = Math.min(10, score);
            notes.add("Test coverage: %d examples, assertions=%s → %d/10".formatted(
                    testCount,
                    ASSERTION_PATTERN.matcher(skillMd).find() ? "yes" : "no",
                    score));
        } catch (Exception e) {
            notes.add("Test coverage: Could not read SKILL.md — 0/10");
        }
        return score;
    }

    // ---- SELF-CONTAINMENT (0-15) ----

    /**
     * Measures whether the skill can run standalone without depending on other skills.
     *
     * Checks:
     * - Does the code call tools.X? How many distinct tools?
     * - Are those tools standard infrastructure (http_request, web_search) or other skills?
     * - Can it produce output without any tool calls succeeding?
     *
     * 15 = fully self-contained (no tools.X calls)
     * 10 = calls only standard infrastructure tools (http, web_search)
     *  5 = calls 1-2 other skills
     *  0 = deep dependency chain (3+ skill calls)
     */
    public int assessSelfContainment(SkillPackage skill, List<String> notes) {
        var code = skill.groovyCode();
        var matcher = TOOL_CALL_PATTERN.matcher(code);

        var toolCalls = new LinkedHashSet<String>();
        while (matcher.find()) {
            toolCalls.add(matcher.group(1));
        }

        if (toolCalls.isEmpty()) {
            notes.add("Self-containment: FULL — no external tool calls (15/15)");
            return 15;
        }

        // Classify tool calls: infrastructure vs skill dependencies
        var infraTools = Set.of("http_request", "web_search", "web_content_extract",
                "browser", "shell", "file_read", "file_write", "list_available_tools");
        var infraCalls = new ArrayList<String>();
        var skillCalls = new ArrayList<String>();

        for (var tool : toolCalls) {
            if (infraTools.contains(tool)) {
                infraCalls.add(tool);
            } else {
                skillCalls.add(tool);
            }
        }

        int score;
        if (skillCalls.isEmpty()) {
            score = 10;
            notes.add("Self-containment: uses %d infra tool(s) [%s] — no skill deps (10/15)".formatted(
                    infraCalls.size(), String.join(", ", infraCalls)));
        } else if (skillCalls.size() <= 2) {
            score = 5;
            notes.add("Self-containment: depends on %d skill(s) [%s] (5/15)".formatted(
                    skillCalls.size(), String.join(", ", skillCalls)));
        } else {
            score = 0;
            notes.add("Self-containment: FAIL — deep chain, depends on %d skills [%s] (0/15)".formatted(
                    skillCalls.size(), String.join(", ", skillCalls)));
        }

        return score;
    }

    // ---- PORTABILITY (0-15) ----

    /**
     * Measures whether a skill is fully portable: can someone drop it in and it just works?
     * Domain-specific skills are fine — what matters is that all configuration, URLs,
     * and resources needed to run are INSIDE the skill, not scattered across external deps.
     *
     * Checks:
     * - Are all URLs/endpoints embedded in the code (not in external config)?
     * - Does the skill define clear input params and not assume external state?
     * - Is there a clear output contract (structured return)?
     * - Does the description/metadata fully explain what it does and when to use it?
     *
     * 15 = fully portable — all config inline, clear I/O contract, good docs
     * 10 = mostly portable — minor external assumptions
     *  5 = partially portable — missing inline config or unclear contract
     *  0 = not portable — relies on external state/config/skills
     */
    public int assessUniversality(SkillPackage skill, List<String> notes) {
        var code = skill.groovyCode();
        var codeLower = code.toLowerCase();
        int score = 0;
        var findings = new ArrayList<String>();

        // 1. URLs/endpoints are inline in the code (3 pts)
        //    If the skill calls HTTP, check that the URL is in the code itself
        boolean usesHttp = codeLower.contains("http") || codeLower.contains("url");
        boolean hasInlineUrl = Pattern.compile("https?://[^\"'\\s]+").matcher(code).find();
        if (!usesHttp) {
            score += 3; // doesn't need URLs — fine
        } else if (hasInlineUrl) {
            score += 3; // URLs are embedded
            findings.add("URLs inline");
        } else {
            findings.add("uses HTTP but no inline URLs — may depend on external config");
        }

        // 2. Clear input contract — params are validated and documented (3 pts)
        boolean hasParamGet = codeLower.contains("params.get");
        boolean hasParamValidation = PARAM_VALIDATION_PATTERN.matcher(code).find();
        if (hasParamGet && hasParamValidation) {
            score += 3;
        } else if (hasParamGet) {
            score += 1;
            findings.add("params used but not validated");
        } else {
            score += 2; // no params needed — self-contained
        }

        // 3. Clear output contract — structured return (3 pts)
        boolean hasReturn = code.contains("return ");
        boolean hasStructuredOutput = STRUCTURED_OUTPUT_PATTERN.matcher(code).find();
        if (hasReturn && hasStructuredOutput) {
            score += 3;
        } else if (hasReturn) {
            score += 2;
        } else {
            findings.add("no clear return/output contract");
        }

        // 4. Metadata completeness — description, triggerWhen, category, tags (3 pts)
        int metaScore = 0;
        if (!skill.description().isBlank() && skill.description().length() > 20) metaScore++;
        if (!skill.category().equals("uncategorized")) metaScore++;
        if (!skill.tags().isEmpty()) metaScore++;
        score += metaScore;
        if (metaScore < 3) findings.add("incomplete metadata (%d/3)".formatted(metaScore));

        // 5. No reliance on external state — no env vars, no file reads, no DB (3 pts)
        boolean readsEnv = codeLower.contains("system.getenv") || codeLower.contains("env.");
        boolean readsFiles = codeLower.contains("file.read") || codeLower.contains("new file(");
        boolean usesDb = codeLower.contains("jdbc") || codeLower.contains("datasource")
                || codeLower.contains("connection");
        if (!readsEnv && !readsFiles && !usesDb) {
            score += 3;
        } else {
            if (readsEnv) findings.add("reads env vars");
            if (readsFiles) findings.add("reads local files");
            if (usesDb) findings.add("uses database");
        }

        score = Math.min(15, score);
        var findingsStr = findings.isEmpty() ? "fully portable" : String.join(", ", findings);
        notes.add("Portability: [%s] → %d/15".formatted(findingsStr, score));
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
