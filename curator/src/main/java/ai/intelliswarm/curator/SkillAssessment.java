package ai.intelliswarm.curator;

import java.util.List;

/**
 * Full assessment of a single skill across 7 dimensions.
 */
public record SkillAssessment(
        SkillPackage skill,
        int executionScore,        // 0-20: compiles? runs? correct output?
        int effectivenessScore,    // 0-15: historical success rate
        int codeQualityScore,      // 0-15: structure, safety, error handling
        int testCoverageScore,     // 0-10: examples + assertions
        int uniquenessScore,       // 0-10: how different from peers
        int selfContainmentScore,  // 0-15: can it run without other skills?
        int universalityScore,     // 0-15: is it domain-agnostic?
        int totalScore,            // 0-100
        String grade,              // A/B/C/D/F
        boolean passesCurationBar, // totalScore >= PUBLICATION_BAR
        String groupName,
        int rankInGroup,
        List<String> assessmentNotes
) {
    public static String gradeFor(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }
}
