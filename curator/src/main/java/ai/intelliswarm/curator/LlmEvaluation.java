package ai.intelliswarm.curator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Result of an LLM-as-judge evaluation of a single skill.
 *
 * The judge analyses:
 *   1. Tool definition accuracy — does SKILL.md match what the code actually does?
 *   2. API call quality — how the tool calls external APIs, param construction, error handling
 *   3. Response handling — how the tool parses/processes API responses
 *   4. Output quality — is the final output useful, well-structured, complete?
 *   5. Overall usefulness — would a real user benefit from this tool?
 */
public record LlmEvaluation(
        String skillSlug,

        // ---- Scores (each 0-10) ----
        int toolDefinitionAccuracy,    // Does the description/metadata match code behaviour?
        int apiCallQuality,            // Are the right APIs called with correct params?
        int responseHandling,          // Does the code properly parse API responses?
        int outputQuality,             // Is the output useful, structured, complete?
        int usefulnessScore,           // Overall: would a real user benefit?

        int overallScore,              // Weighted aggregate (0-100)

        // ---- Narrative ----
        String verdict,                // One-line verdict: e.g. "Useful but fragile"
        String definitionAnalysis,     // Detailed analysis of SKILL.md vs code behaviour
        String apiCallAnalysis,        // Detailed analysis of each API call made
        String responseAnalysis,       // How the tool handles what comes back
        String outputAnalysis,         // Quality of the final output
        String usefulnessAnalysis,     // Overall usefulness rationale

        List<String> strengths,        // What the tool does well
        List<String> weaknesses,       // What the tool does poorly
        List<String> recommendations,  // Actionable improvement suggestions

        // ---- Meta ----
        String model,                  // Which LLM model was the judge
        LocalDateTime evaluatedAt,
        long evaluationTimeMs
) {
    /**
     * Convert to a Map for JSON serialization alongside _assessment.json.
     */
    public Map<String, Object> toMap() {
        return Map.ofEntries(
                Map.entry("skillSlug", skillSlug),
                Map.entry("scores", Map.of(
                        "toolDefinitionAccuracy", toolDefinitionAccuracy,
                        "apiCallQuality", apiCallQuality,
                        "responseHandling", responseHandling,
                        "outputQuality", outputQuality,
                        "usefulnessScore", usefulnessScore,
                        "overall", overallScore
                )),
                Map.entry("verdict", verdict),
                Map.entry("definitionAnalysis", definitionAnalysis),
                Map.entry("apiCallAnalysis", apiCallAnalysis),
                Map.entry("responseAnalysis", responseAnalysis),
                Map.entry("outputAnalysis", outputAnalysis),
                Map.entry("usefulnessAnalysis", usefulnessAnalysis),
                Map.entry("strengths", strengths),
                Map.entry("weaknesses", weaknesses),
                Map.entry("recommendations", recommendations),
                Map.entry("model", model),
                Map.entry("evaluatedAt", evaluatedAt.toString()),
                Map.entry("evaluationTimeMs", evaluationTimeMs)
        );
    }

    public String gradeFor() {
        if (overallScore >= 90) return "A";
        if (overallScore >= 80) return "B";
        if (overallScore >= 70) return "C";
        if (overallScore >= 60) return "D";
        return "F";
    }
}
