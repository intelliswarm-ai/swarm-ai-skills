package ai.intelliswarm.curator;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A skill package loaded from a directory containing SKILL.md + _meta.json.
 */
public record SkillPackage(
        String slug,
        String displayName,
        String description,
        String category,
        List<String> tags,
        String status,
        String skillType,
        int usageCount,
        int successCount,
        String groovyCode,
        QualityScore qualityScore,
        String version,
        LocalDateTime createdAt,
        Path directory
) {
    public record QualityScore(
            int documentation,
            int testCoverage,
            int errorHandling,
            int codeComplexity,
            int outputFormat,
            int total,
            String grade
    ) {}

    /** Effective success rate (0.0-1.0). Skills with 0 usage get 0. */
    public double successRate() {
        return usageCount > 0 ? (double) successCount / usageCount : 0.0;
    }
}
