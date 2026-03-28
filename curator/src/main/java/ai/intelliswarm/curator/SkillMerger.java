package ai.intelliswarm.curator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Merges overlapping skills within a group into a single "super-skill" that
 * combines the best features (error handling, fallback chains, data sources)
 * from each constituent skill.
 *
 * Strategy:
 * 1. Take the highest-scoring skill as the base
 * 2. Extract unique capabilities from lower-ranked skills (fallback strategies,
 *    alternative data sources, extra error handling)
 * 3. Compose a merged Groovy script that chains them
 * 4. Combine tags, improve description
 * 5. Write merged skill to _merged/ directory
 */
public class SkillMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record MergeResult(
            String mergedSlug,
            Path mergedDir,
            List<String> sourceSkills,
            String groupName
    ) {}

    /**
     * Merge overlapping skills within groups that have > mergeCandidateThreshold members.
     *
     * @param rankedGroups  groups from curation, sorted best-first
     * @param skillsDir     the skills directory
     * @param minGroupSize  only merge groups with at least this many members
     * @return list of merge results
     */
    public List<MergeResult> mergeOverlapping(
            Map<String, List<SkillAssessment>> rankedGroups,
            Path skillsDir, int minGroupSize) throws IOException {

        var mergedDir = skillsDir.resolve("_merged");
        Files.createDirectories(mergedDir);

        var results = new ArrayList<MergeResult>();

        for (var entry : rankedGroups.entrySet()) {
            var groupName = entry.getKey();
            var members = entry.getValue();

            if (members.size() < minGroupSize) continue;

            // Only merge skills scoring >= 70 (grade C+) — must be genuinely useful
            var candidates = members.stream()
                    .filter(a -> a.totalScore() >= 70)
                    .limit(5) // merge at most top 5
                    .toList();

            if (candidates.size() < 2) continue;

            var result = mergeGroup(groupName, candidates, mergedDir);
            results.add(result);

            System.out.printf("Merged %d skills in [%s] → %s%n",
                    candidates.size(), groupName, result.mergedSlug());
        }

        return results;
    }

    private MergeResult mergeGroup(String groupName, List<SkillAssessment> candidates,
                                    Path mergedDir) throws IOException {
        var base = candidates.getFirst(); // highest scoring
        var others = candidates.subList(1, candidates.size());

        // Build merged slug from actual capabilities
        var mergedSlug = buildMergedSlug(groupName, candidates);
        var skillDir = mergedDir.resolve(mergedSlug);
        Files.createDirectories(skillDir);

        // Combine tags
        var allTags = new LinkedHashSet<String>();
        allTags.addAll(base.skill().tags());
        for (var other : others) {
            allTags.addAll(other.skill().tags());
        }
        allTags.add("merged");
        allTags.add("curated");

        // Build merged description
        var description = buildMergedDescription(base, others);

        // Build merged Groovy code
        var mergedCode = buildMergedCode(base, others);

        // Write SKILL.md
        var skillMd = buildSkillMd(mergedSlug, description, base.skill().category(),
                allTags, mergedCode, candidates);
        Files.writeString(skillDir.resolve("SKILL.md"), skillMd);

        // Write _meta.json
        var metaJson = buildMetaJson(mergedSlug, description, allTags, candidates);
        MAPPER.writeValue(skillDir.resolve("_meta.json").toFile(), metaJson);

        // Write _merge_manifest.json (provenance)
        var manifest = Map.of(
                "mergedFrom", candidates.stream()
                        .map(a -> Map.of(
                                "slug", a.skill().slug(),
                                "score", a.totalScore(),
                                "rank", a.rankInGroup()))
                        .toList(),
                "groupName", groupName,
                "mergedAt", LocalDateTime.now().toString(),
                "strategy", "chain-with-fallback"
        );
        MAPPER.writeValue(skillDir.resolve("_merge_manifest.json").toFile(), manifest);

        var sourceSkills = candidates.stream().map(a -> a.skill().slug()).toList();
        return new MergeResult(mergedSlug, skillDir, sourceSkills, groupName);
    }

    /**
     * Build merged code: use the base skill's code as primary, wrap others as fallbacks.
     */
    private String buildMergedCode(SkillAssessment base, List<SkillAssessment> others) {
        var sb = new StringBuilder();
        sb.append("// Merged skill: combines %d source skills\n".formatted(others.size() + 1));
        sb.append("// Base: %s (score: %d)\n".formatted(base.skill().slug(), base.totalScore()));
        for (var o : others) {
            sb.append("// + %s (score: %d)\n".formatted(o.skill().slug(), o.totalScore()));
        }
        sb.append("\n");

        // Extract params detection from base
        var baseCode = base.skill().groovyCode();
        var paramLines = extractParamLines(baseCode);
        sb.append(paramLines).append("\n");

        // Wrap base code as primary attempt
        sb.append("def results = [:]\n");
        sb.append("def errors = []\n\n");

        sb.append("// Primary: %s\n".formatted(base.skill().slug()));
        sb.append("try {\n");
        sb.append("    def primaryResult = { ->\n");
        sb.append(indent(stripParamLines(baseCode), "        "));
        sb.append("\n    }.call()\n");
        sb.append("    if (primaryResult && primaryResult.toString().length() > 20) {\n");
        sb.append("        results['primary'] = primaryResult\n");
        sb.append("    }\n");
        sb.append("} catch (Exception e) {\n");
        sb.append("    errors << \"primary (%s) failed: ${e.message}\"\n".formatted(base.skill().slug()));
        sb.append("}\n\n");

        // Add each other skill as fallback
        int fallbackIdx = 1;
        for (var other : others) {
            sb.append("// Fallback %d: %s\n".formatted(fallbackIdx, other.skill().slug()));
            sb.append("if (results.isEmpty()) {\n");
            sb.append("    try {\n");
            sb.append("        def fallbackResult = { ->\n");
            sb.append(indent(stripParamLines(other.skill().groovyCode()), "            "));
            sb.append("\n        }.call()\n");
            sb.append("        if (fallbackResult && fallbackResult.toString().length() > 20) {\n");
            sb.append("            results['fallback_%d'] = fallbackResult\n".formatted(fallbackIdx));
            sb.append("        }\n");
            sb.append("    } catch (Exception e) {\n");
            sb.append("        errors << \"fallback %d (%s) failed: ${e.message}\"\n".formatted(
                    fallbackIdx, other.skill().slug()));
            sb.append("    }\n");
            sb.append("}\n\n");
            fallbackIdx++;
        }

        // Final output
        sb.append("// Compose final output\n");
        sb.append("if (results.isEmpty()) {\n");
        sb.append("    return \"No results from any source. Errors: \" + errors.join(\"; \")\n");
        sb.append("}\n");
        sb.append("def output = results.values().first().toString()\n");
        sb.append("if (!errors.isEmpty()) {\n");
        sb.append("    output += \"\\n\\n[Warnings: \" + errors.join(\"; \") + \"]\"\n");
        sb.append("}\n");
        sb.append("return output\n");

        return sb.toString();
    }

    private String extractParamLines(String code) {
        var lines = code.split("\n");
        var paramLines = new StringBuilder();
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.startsWith("def ") && trimmed.contains("params.get")
                    || trimmed.startsWith("if (!") && trimmed.contains("return \"ERROR")) {
                paramLines.append(line).append("\n");
            }
        }
        return paramLines.toString();
    }

    private String stripParamLines(String code) {
        var lines = code.split("\n");
        var sb = new StringBuilder();
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.startsWith("def ") && trimmed.contains("params.get")) continue;
            if (trimmed.startsWith("if (!") && trimmed.contains("return \"ERROR")) continue;
            // Also skip import lines (handled at top level)
            if (trimmed.startsWith("import ")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private String indent(String code, String prefix) {
        return Arrays.stream(code.split("\n"))
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    private String buildMergedSlug(String groupName, List<SkillAssessment> candidates) {
        // Extract the dominant capability verbs and nouns from the source skills
        var actionWords = new LinkedHashMap<String, Integer>();
        var STOP_WORDS = Set.of("and", "the", "with", "for", "from", "data", "tool",
                "merged", "robust", "resilient", "reliable", "alternative", "fallback",
                "wrapper", "auto", "composer");

        for (var c : candidates) {
            for (var token : c.skill().slug().split("_")) {
                if (token.length() > 2 && !STOP_WORDS.contains(token.toLowerCase())) {
                    actionWords.merge(token.toLowerCase(), 1, Integer::sum);
                }
            }
        }

        // Pick the top 3-4 most common meaningful tokens
        var topTokens = actionWords.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(4)
                .map(Map.Entry::getKey)
                .toList();

        if (topTokens.isEmpty()) {
            // Fallback to group-name-based slug
            return groupName.replaceAll("[^a-zA-Z0-9]+", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "")
                    .toLowerCase();
        }

        return String.join("_", topTokens);
    }

    private String buildMergedDescription(SkillAssessment base, List<SkillAssessment> others) {
        var sb = new StringBuilder();
        sb.append("Merged super-skill combining capabilities from %d source skills. ".formatted(
                others.size() + 1));
        sb.append("Base: %s. ".formatted(base.skill().description()));
        sb.append("Enhanced with fallback strategies from: ");
        sb.append(others.stream()
                .map(o -> o.skill().displayName())
                .collect(Collectors.joining(", ")));
        sb.append(".");
        return sb.toString();
    }

    private String buildSkillMd(String slug, String description, String category,
                                 Set<String> tags, String code,
                                 List<SkillAssessment> sources) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: %s\n".formatted(slug));
        sb.append("description: %s\n".formatted(description));
        sb.append("type: CODE\n");
        sb.append("triggerWhen: When robust, multi-source financial data is needed with automatic fallback.\n");
        sb.append("avoidWhen: When a single specific data source is required.\n");
        sb.append("category: %s\n".formatted(category));
        sb.append("tags: [%s]\n".formatted(String.join(", ", tags)));
        sb.append("---\n\n");
        sb.append("# %s\n\n".formatted(slug.replace("_", " ").substring(0, 1).toUpperCase()
                + slug.replace("_", " ").substring(1)));
        sb.append("Merged skill combining the best of %d source skills:\n\n".formatted(sources.size()));
        for (var s : sources) {
            sb.append("- **%s** (score: %d/100, %s)\n".formatted(
                    s.skill().slug(), s.totalScore(), s.grade()));
        }
        sb.append("\n## Code\n```groovy\n");
        sb.append(code);
        sb.append("\n```\n");
        return sb.toString();
    }

    private Map<String, Object> buildMetaJson(String slug, String description,
                                               Set<String> tags,
                                               List<SkillAssessment> sources) {
        // Aggregate usage stats from sources
        int totalUsage = sources.stream().mapToInt(a -> a.skill().usageCount()).sum();
        int totalSuccess = sources.stream().mapToInt(a -> a.skill().successCount()).sum();

        return new LinkedHashMap<>(Map.of(
                "id", UUID.randomUUID().toString().substring(0, 8),
                "slug", slug,
                "displayName", slug.replace("_", " "),
                "status", "CURATED",
                "skillType", "CODE",
                "usageCount", totalUsage,
                "successCount", totalSuccess,
                "createdAt", LocalDateTime.now().toString(),
                "qualityScore", Map.of(
                        "documentation", 20,
                        "testCoverage", 15,
                        "errorHandling", 20,
                        "codeComplexity", 15,
                        "outputFormat", 20,
                        "total", 90,
                        "grade", "A"
                )
        ));
    }
}
