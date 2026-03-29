package ai.intelliswarm.curator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * LLM-as-judge evaluator for auto-created skills.
 *
 * Sends the full skill context (definition, code, API call traces, live test output)
 * to an LLM and asks it to produce a structured evaluation covering:
 *
 *   1. Tool Definition Accuracy — does SKILL.md match actual code behaviour?
 *   2. API Call Quality — are the right APIs called with proper parameters?
 *   3. Response Handling — does the code correctly parse what comes back?
 *   4. Output Quality — is the final output well-structured and complete?
 *   5. Usefulness — would a real user benefit from this tool?
 */
public class LlmJudge {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmJudgeConfig config;
    private final HttpClient httpClient;

    public LlmJudge(LlmJudgeConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Evaluate a single skill using the LLM judge.
     *
     * @param skill         the skill package (code, metadata)
     * @param assessment    the structural assessment (scores, notes)
     * @param liveResult    the live test result (may be null if not tested)
     * @return LlmEvaluation with detailed scores and narrative
     */
    public LlmEvaluation evaluate(SkillPackage skill,
                                   SkillAssessment assessment,
                                   LiveTestRunner.LiveTestResult liveResult) throws IOException {
        long startTime = System.currentTimeMillis();

        // Build the full context for the judge
        String skillMd = readSkillMd(skill);
        String prompt = buildEvaluationPrompt(skill, skillMd, assessment, liveResult);

        // Call the LLM
        String response = callLlm(prompt);

        // Parse the structured JSON response
        LlmEvaluation evaluation = parseEvaluation(skill.slug(), response, startTime);

        return evaluation;
    }

    /**
     * Evaluate all published skills in batch.
     */
    public List<LlmEvaluation> evaluateAll(List<SkillAssessment> assessments,
                                            List<LiveTestRunner.LiveTestResult> liveResults) {
        // Build lookup for live results
        var liveMap = new HashMap<String, LiveTestRunner.LiveTestResult>();
        if (liveResults != null) {
            for (var r : liveResults) liveMap.put(r.slug(), r);
        }

        var evaluations = new ArrayList<LlmEvaluation>();
        for (var assessment : assessments) {
            var skill = assessment.skill();
            var live = liveMap.get(skill.slug());

            System.out.printf("  LLM JUDGE: %-40s ... ", skill.slug());
            try {
                var eval = evaluate(skill, assessment, live);
                evaluations.add(eval);
                System.out.printf("DONE (%dms) — %d/100 \"%s\"%n",
                        eval.evaluationTimeMs(), eval.overallScore(), eval.verdict());
            } catch (Exception e) {
                System.out.printf("ERROR — %s%n", firstLine(e.getMessage()));
                evaluations.add(errorEvaluation(skill.slug(), e.getMessage()));
            }
        }

        return evaluations;
    }

    // ---- PROMPT CONSTRUCTION ----

    private String buildEvaluationPrompt(SkillPackage skill, String skillMd,
                                          SkillAssessment assessment,
                                          LiveTestRunner.LiveTestResult liveResult) {
        var sb = new StringBuilder();

        sb.append("""
                You are an expert software evaluator acting as an LLM-as-judge for auto-generated AI agent tools.

                Your task is to perform a rigorous, detailed evaluation of the following tool/skill.
                You must evaluate it across 5 dimensions, each scored 0-10:

                1. **Tool Definition Accuracy** (0-10): Does the SKILL.md description, metadata (name, \
                triggerWhen, avoidWhen, category, tags) accurately describe what the code actually does? \
                Are the parameters well-documented? Does the output-template match actual output?

                2. **API Call Quality** (0-10): Examine every API call the tool makes. \
                Are the right endpoints/services being called? Are query parameters correctly constructed? \
                Is authentication handled properly? Are rate limits considered? \
                Is the call pattern efficient (no unnecessary calls)?

                3. **Response Handling** (0-10): After each API call, how well does the code handle the response? \
                Does it parse the response correctly? Handle error responses? Handle missing/null fields? \
                Handle unexpected response formats? Is there fallback logic?

                4. **Output Quality** (0-10): Is the final output useful and well-structured? \
                Does it contain the data a user would actually want? Is it formatted readably? \
                Is it complete or does it leave out important information?

                5. **Usefulness Score** (0-10): Overall, would a real user find this tool valuable? \
                Does it solve a real problem? Could a user rely on it? Is it better than alternatives \
                (e.g., just doing a web search manually)?

                """);

        // Skill definition
        sb.append("## SKILL DEFINITION (SKILL.md)\n\n```markdown\n");
        sb.append(skillMd);
        sb.append("\n```\n\n");

        // Metadata
        sb.append("## METADATA\n\n");
        sb.append("- **Slug:** %s\n".formatted(skill.slug()));
        sb.append("- **Category:** %s\n".formatted(skill.category()));
        sb.append("- **Tags:** %s\n".formatted(skill.tags()));
        sb.append("- **Usage count:** %d\n".formatted(skill.usageCount()));
        sb.append("- **Success count:** %d\n".formatted(skill.successCount()));
        sb.append("- **Success rate:** %.1f%%\n".formatted(skill.successRate() * 100));
        sb.append("- **Quality score (pre-existing):** %d/100\n\n".formatted(skill.qualityScore().total()));

        // Structural assessment
        sb.append("## STRUCTURAL ASSESSMENT (automated scores)\n\n");
        sb.append("- Execution: %d/20\n".formatted(assessment.executionScore()));
        sb.append("- Effectiveness: %d/15\n".formatted(assessment.effectivenessScore()));
        sb.append("- Code Quality: %d/15\n".formatted(assessment.codeQualityScore()));
        sb.append("- Test Coverage: %d/10\n".formatted(assessment.testCoverageScore()));
        sb.append("- Uniqueness: %d/10\n".formatted(assessment.uniquenessScore()));
        sb.append("- Self-Containment: %d/15\n".formatted(assessment.selfContainmentScore()));
        sb.append("- Universality: %d/15\n".formatted(assessment.universalityScore()));
        sb.append("- **Total: %d/100 (Grade: %s)**\n\n".formatted(
                assessment.totalScore(), assessment.grade()));

        sb.append("### Assessment Notes\n");
        for (var note : assessment.assessmentNotes()) {
            sb.append("- %s\n".formatted(note));
        }
        sb.append("\n");

        // Live test results — THE CRITICAL PART for API call evaluation
        if (liveResult != null) {
            sb.append("## LIVE TEST RESULTS (real API calls — NO mocks)\n\n");
            sb.append("- **Passed:** %s\n".formatted(liveResult.passed()));
            sb.append("- **Execution time:** %dms\n".formatted(liveResult.executionTimeMs()));
            sb.append("- **Test parameters:** %s\n".formatted(liveResult.testParams()));
            sb.append("- **Output length:** %d chars\n".formatted(liveResult.outputLength()));
            sb.append("- **Contains real data:** %s\n".formatted(liveResult.containsRealData()));
            sb.append("- **Output matches template:** %s\n".formatted(liveResult.outputMatchesTemplate()));

            if (liveResult.error() != null && !liveResult.error().isBlank()) {
                sb.append("- **Error:** %s\n".formatted(liveResult.error()));
            }

            sb.append("\n### Live Test Notes\n");
            for (var note : liveResult.notes()) {
                sb.append("- %s\n".formatted(note));
            }

            // API Call Traces — detailed record of every API call
            if (liveResult.apiCallTraces() != null && !liveResult.apiCallTraces().isEmpty()) {
                sb.append("\n### API CALL TRACES (every external call recorded)\n\n");
                sb.append("These are the actual API calls made during execution, with timing and response data:\n\n");
                int callNum = 1;
                for (var trace : liveResult.apiCallTraces()) {
                    sb.append("**Call #%d: %s**\n".formatted(callNum++, trace.toolName()));
                    sb.append("- Method: %s\n".formatted(trace.method()));
                    sb.append("- URL: `%s`\n".formatted(trace.url()));
                    sb.append("- Parameters: %s\n".formatted(trace.params()));
                    sb.append("- Success: %s\n".formatted(trace.success()));
                    sb.append("- Duration: %dms\n".formatted(trace.durationMs()));
                    sb.append("- Response length: %d chars\n".formatted(trace.responseLength()));
                    sb.append("- Response snippet: `%s`\n\n".formatted(trace.responseSnippet()));
                }
                sb.append("""
                        IMPORTANT: The above traces show EXACTLY what API calls the tool made and \
                        what it received back. Use this to evaluate:
                        - Are the right APIs/endpoints being called?
                        - Are query parameters correctly constructed?
                        - Are the responses meaningful and contain the expected data?
                        - Is the tool making unnecessary or redundant calls?
                        - How efficient is the API call pattern?

                        """);
            }

            if (liveResult.output() != null) {
                // Include the actual output (truncated to avoid token explosion)
                var output = liveResult.output();
                var truncated = output.length() > 3000
                        ? output.substring(0, 3000) + "\n... [truncated, total " + output.length() + " chars]"
                        : output;
                sb.append("\n### ACTUAL OUTPUT from live test\n\n```\n");
                sb.append(truncated);
                sb.append("\n```\n\n");
                sb.append("""
                        IMPORTANT: The above is the REAL output produced when this tool was executed \
                        with real API calls. Compare this output against the output-template in SKILL.md. \
                        Does it match? Is the data accurate and useful? Would a user be satisfied with this result?
                        """);
            }
        } else {
            sb.append("## LIVE TEST RESULTS\n\nNo live test was performed for this skill.\n\n");
        }

        // Response format instruction
        sb.append("""

                ## YOUR EVALUATION

                Respond with ONLY a JSON object (no markdown fencing, no explanation outside JSON) \
                with exactly this structure:

                {
                  "toolDefinitionAccuracy": <0-10>,
                  "apiCallQuality": <0-10>,
                  "responseHandling": <0-10>,
                  "outputQuality": <0-10>,
                  "usefulnessScore": <0-10>,
                  "verdict": "<one-line summary, max 80 chars>",
                  "definitionAnalysis": "<2-4 paragraphs analysing SKILL.md accuracy vs code>",
                  "apiCallAnalysis": "<2-4 paragraphs analysing each API call: what is called, \
                how params are built, what comes back, is it the right approach>",
                  "responseAnalysis": "<2-4 paragraphs on how the tool handles API responses: \
                parsing, error handling, data extraction, edge cases>",
                  "outputAnalysis": "<2-4 paragraphs on the quality and usefulness of the final output>",
                  "usefulnessAnalysis": "<2-4 paragraphs on overall usefulness: who benefits, \
                when, reliability, vs alternatives>",
                  "strengths": ["strength1", "strength2", ...],
                  "weaknesses": ["weakness1", "weakness2", ...],
                  "recommendations": ["actionable recommendation 1", "actionable recommendation 2", ...]
                }

                Be thorough and specific. Reference actual code lines, API endpoints, and output content \
                in your analysis. Do not be generic — every statement should be grounded in evidence \
                from the skill definition and live test output above.
                """);

        return sb.toString();
    }

    // ---- LLM API CALLS ----

    private String callLlm(String prompt) throws IOException {
        return switch (config.provider()) {
            case "openai" -> callOpenAi(prompt);
            default -> callAnthropic(prompt);
        };
    }

    private String callAnthropic(String prompt) throws IOException {
        var requestBody = MAPPER.writeValueAsString(Map.of(
                "model", config.model(),
                "max_tokens", config.maxTokens(),
                "temperature", config.temperature(),
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        var request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/v1/messages"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Anthropic API error %d: %s".formatted(
                        response.statusCode(), truncate(response.body(), 500)));
            }

            var json = MAPPER.readTree(response.body());
            var content = json.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText();
            }
            throw new IOException("Unexpected Anthropic response format: " + truncate(response.body(), 300));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM call interrupted", e);
        }
    }

    private String callOpenAi(String prompt) throws IOException {
        var requestBody = MAPPER.writeValueAsString(Map.of(
                "model", config.model(),
                "max_tokens", config.maxTokens(),
                "temperature", config.temperature(),
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        var request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/v1/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OpenAI API error %d: %s".formatted(
                        response.statusCode(), truncate(response.body(), 500)));
            }

            var json = MAPPER.readTree(response.body());
            var choices = json.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText();
            }
            throw new IOException("Unexpected OpenAI response format: " + truncate(response.body(), 300));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM call interrupted", e);
        }
    }

    // ---- RESPONSE PARSING ----

    private LlmEvaluation parseEvaluation(String slug, String rawResponse, long startTime) throws IOException {
        // Strip markdown code fences if present
        var cleaned = rawResponse.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.strip();

        JsonNode json;
        try {
            json = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            throw new IOException("Failed to parse LLM JSON response: " + firstLine(e.getMessage())
                    + "\nRaw response (first 500 chars): " + truncate(rawResponse, 500));
        }

        long elapsed = System.currentTimeMillis() - startTime;

        int defAccuracy = clamp(json.path("toolDefinitionAccuracy").asInt(0), 0, 10);
        int apiQuality = clamp(json.path("apiCallQuality").asInt(0), 0, 10);
        int respHandling = clamp(json.path("responseHandling").asInt(0), 0, 10);
        int outputQual = clamp(json.path("outputQuality").asInt(0), 0, 10);
        int usefulness = clamp(json.path("usefulnessScore").asInt(0), 0, 10);

        // Weighted overall: definition 15%, API calls 25%, response handling 20%, output 20%, usefulness 20%
        int overall = (int) Math.round(
                defAccuracy * 1.5
                + apiQuality * 2.5
                + respHandling * 2.0
                + outputQual * 2.0
                + usefulness * 2.0
        );
        overall = clamp(overall, 0, 100);

        return new LlmEvaluation(
                slug,
                defAccuracy,
                apiQuality,
                respHandling,
                outputQual,
                usefulness,
                overall,
                json.path("verdict").asText("No verdict"),
                json.path("definitionAnalysis").asText(""),
                json.path("apiCallAnalysis").asText(""),
                json.path("responseAnalysis").asText(""),
                json.path("outputAnalysis").asText(""),
                json.path("usefulnessAnalysis").asText(""),
                jsonArrayToList(json.path("strengths")),
                jsonArrayToList(json.path("weaknesses")),
                jsonArrayToList(json.path("recommendations")),
                config.model(),
                LocalDateTime.now(),
                elapsed
        );
    }

    private LlmEvaluation errorEvaluation(String slug, String errorMsg) {
        return new LlmEvaluation(
                slug, 0, 0, 0, 0, 0, 0,
                "Evaluation failed: " + firstLine(errorMsg),
                "", "", "", "", "",
                List.of(), List.of(), List.of("Fix evaluation error: " + firstLine(errorMsg)),
                config.model(), LocalDateTime.now(), 0
        );
    }

    // ---- HELPERS ----

    private String readSkillMd(SkillPackage skill) {
        try {
            return Files.readString(skill.directory().resolve("SKILL.md"));
        } catch (IOException e) {
            return "(SKILL.md not readable: " + e.getMessage() + ")";
        }
    }

    private List<String> jsonArrayToList(JsonNode node) {
        var list = new ArrayList<String>();
        if (node != null && node.isArray()) {
            for (var item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String firstLine(String msg) {
        if (msg == null) return "unknown";
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, Math.min(nl, 150)) : msg.substring(0, Math.min(msg.length(), 150));
    }
}
