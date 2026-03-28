package ai.intelliswarm.curator;

import java.util.List;

/**
 * Full assessment of a single skill across 5 dimensions.
 */
public record SkillAssessment(
        SkillPackage skill,
        int executionScore,       // 0-25: compiles? runs? correct output?
        int effectivenessScore,   // 0-25: historical success rate
        int codeQualityScore,     // 0-20: from existing quality score
        int testCoverageScore,    // 0-15: test count + assertion quality
        int uniquenessScore,      // 0-15: how different from peers
        int totalScore,           // 0-100
        String grade,             // A/B/C/D/F
        boolean passesCurationBar,// totalScore >= 60
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
