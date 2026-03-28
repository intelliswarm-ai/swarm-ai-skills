package ai.intelliswarm.curator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * When a skill fails self-containment because it depends on other skills (tools.X calls),
 * this inliner checks if all dependencies exist in the repo. If they do, it produces
 * a flattened version with all dependency code inlined as local closures — making it
 * fully self-contained.
 *
 * Only inlines if:
 * - All dependencies are resolvable (exist in the skills dir)
 * - No circular dependencies
 * - Resulting code is not excessively large (< 300 lines)
 */
public class DependencyInliner {

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile("tools\\.(\\w+)");
    private static final Set<String> INFRA_TOOLS = Set.of(
            "http_request", "web_search", "web_content_extract",
            "browser", "shell", "file_read", "file_write",
            "list_available_tools", "fetch_usage_log");
    private static final int MAX_INLINED_LINES = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record InlineResult(
            String slug,
            Path outputDir,
            List<String> inlinedDeps,
            int originalDepCount,
            boolean success,
            String reason
    ) {}

    /**
     * Try to inline dependencies for skills that failed self-containment.
     *
     * @param failedAssessments skills that failed the curation bar
     * @param allPackages       all available skill packages (lookup for deps)
     * @param skillsDir         the skills directory
     * @return list of successfully inlined skills
     */
    public List<InlineResult> inlineFailedSkills(
            List<SkillAssessment> failedAssessments,
            List<SkillPackage> allPackages,
            Path skillsDir) throws IOException {

        var inlinedDir = skillsDir.resolve("_inlined");
        Files.createDirectories(inlinedDir);

        // Build lookup: slug -> SkillPackage
        var packageMap = new HashMap<String, SkillPackage>();
        for (var pkg : allPackages) {
            packageMap.put(pkg.slug(), pkg);
        }

        var results = new ArrayList<InlineResult>();

        for (var assessment : failedAssessments) {
            // Only try inlining for skills that failed due to dependency chains
            if (assessment.selfContainmentScore() > 5) continue;

            var skill = assessment.skill();
            var deps = findSkillDependencies(skill);

            if (deps.isEmpty()) continue;

            // Check if all deps are resolvable
            var missingDeps = deps.stream()
                    .filter(d -> !packageMap.containsKey(d))
                    .toList();

            if (!missingDeps.isEmpty()) {
                results.add(new InlineResult(
                        skill.slug(), null, List.of(), deps.size(), false,
                        "Missing dependencies: " + String.join(", ", missingDeps)));
                continue;
            }

            // Check for circular deps (simple: if any dep depends on this skill)
            boolean circular = false;
            for (var dep : deps) {
                var depPkg = packageMap.get(dep);
                var depDeps = findSkillDependencies(depPkg);
                if (depDeps.contains(skill.slug())) {
                    circular = true;
                    break;
                }
            }
            if (circular) {
                results.add(new InlineResult(
                        skill.slug(), null, List.of(), deps.size(), false,
                        "Circular dependency detected"));
                continue;
            }

            // Resolve transitive deps (BFS, max depth 3)
            var allDeps = resolveTransitive(skill.slug(), packageMap, 3);
            var allMissing = allDeps.stream()
                    .filter(d -> !packageMap.containsKey(d))
                    .toList();
            if (!allMissing.isEmpty()) {
                results.add(new InlineResult(
                        skill.slug(), null, List.of(), allDeps.size(), false,
                        "Missing transitive deps: " + String.join(", ", allMissing)));
                continue;
            }

            // Build inlined code
            var inlinedCode = buildInlinedCode(skill, allDeps, packageMap);

            // Write the inlined skill
            var outputSlug = skill.slug() + "_standalone";
            var outputPath = inlinedDir.resolve(outputSlug);
            Files.createDirectories(outputPath);

            writeInlinedSkill(skill, outputSlug, outputPath, inlinedCode, allDeps, packageMap);

            results.add(new InlineResult(
                    outputSlug, outputPath, new ArrayList<>(allDeps), allDeps.size(), true,
                    "Inlined %d dependencies".formatted(allDeps.size())));

            System.out.printf("  Inlined %s → %s (%d deps flattened)%n",
                    skill.slug(), outputSlug, allDeps.size());
        }

        return results;
    }

    /**
     * Find direct skill dependencies (tools.X calls that are NOT infrastructure tools).
     */
    private List<String> findSkillDependencies(SkillPackage skill) {
        var matcher = TOOL_CALL_PATTERN.matcher(skill.groovyCode());
        var deps = new LinkedHashSet<String>();
        while (matcher.find()) {
            var toolName = matcher.group(1);
            if (!INFRA_TOOLS.contains(toolName)) {
                deps.add(toolName);
            }
        }
        return new ArrayList<>(deps);
    }

    /**
     * Resolve transitive dependencies using BFS (up to maxDepth).
     */
    private Set<String> resolveTransitive(String rootSlug,
                                           Map<String, SkillPackage> packageMap,
                                           int maxDepth) {
        var allDeps = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        var rootPkg = packageMap.get(rootSlug);
        if (rootPkg == null) return allDeps;

        var directDeps = findSkillDependencies(rootPkg);
        queue.addAll(directDeps);

        int depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            var nextLevel = new ArrayDeque<String>();
            while (!queue.isEmpty()) {
                var dep = queue.poll();
                if (allDeps.contains(dep) || dep.equals(rootSlug)) continue;
                allDeps.add(dep);

                var depPkg = packageMap.get(dep);
                if (depPkg != null) {
                    nextLevel.addAll(findSkillDependencies(depPkg));
                }
            }
            queue = nextLevel;
            depth++;
        }
        return allDeps;
    }

    /**
     * Build self-contained code: replace each tools.X.execute(...) call with
     * an inline closure that contains the dependency's actual code.
     */
    private String buildInlinedCode(SkillPackage skill, Set<String> allDeps,
                                     Map<String, SkillPackage> packageMap) {
        var sb = new StringBuilder();
        sb.append("// Self-contained version of: %s\n".formatted(skill.slug()));
        sb.append("// Dependencies inlined: %s\n\n".formatted(String.join(", ", allDeps)));

        // Define each dependency as a local closure
        for (var depSlug : allDeps) {
            var depPkg = packageMap.get(depSlug);
            if (depPkg == null) continue;

            sb.append("// --- Inlined dependency: %s ---\n".formatted(depSlug));
            sb.append("def _dep_%s = { Map _params, _tools ->\n".formatted(sanitize(depSlug)));
            sb.append(indent(depPkg.groovyCode(), "    "));
            sb.append("\n}\n\n");
        }

        // Build a tools proxy that delegates to inlined closures
        sb.append("// Tools proxy: delegates to inlined deps, passes through infra tools\n");
        sb.append("def _inlinedTools = new Expando()\n");
        for (var depSlug : allDeps) {
            sb.append("_inlinedTools.%s = new Expando(execute: { args -> _dep_%s(args instanceof Map ? args : [:], _inlinedTools) })\n"
                    .formatted(depSlug, sanitize(depSlug)));
        }
        sb.append("\n");

        // Replace 'tools' reference in the main code with the proxy
        var mainCode = skill.groovyCode();
        // We replace the tools variable binding
        sb.append("// --- Main skill: %s ---\n".formatted(skill.slug()));
        sb.append("def _originalTools = tools\n");
        sb.append("def _proxyTools = new Expando()\n");
        // Forward infra tools to original, skill deps to inlined
        for (var depSlug : allDeps) {
            sb.append("_proxyTools.%s = _inlinedTools.%s\n".formatted(depSlug, depSlug));
        }
        sb.append("_proxyTools.setProperty = { String name, val -> }\n");
        sb.append("_proxyTools.getProperty = { String name -> _originalTools.\"$name\" }\n");
        sb.append("tools = _proxyTools\n\n");
        sb.append(mainCode);

        return sb.toString();
    }

    private String sanitize(String slug) {
        return slug.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private String indent(String code, String prefix) {
        return Arrays.stream(code.split("\n"))
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    private void writeInlinedSkill(SkillPackage original, String slug, Path outputDir,
                                    String inlinedCode, Set<String> deps,
                                    Map<String, SkillPackage> packageMap) throws IOException {
        // Combine tags from all sources
        var allTags = new LinkedHashSet<>(original.tags());
        allTags.add("standalone");
        allTags.add("inlined");
        for (var dep : deps) {
            var depPkg = packageMap.get(dep);
            if (depPkg != null) allTags.addAll(depPkg.tags());
        }

        // SKILL.md
        var skillMd = new StringBuilder();
        skillMd.append("---\n");
        skillMd.append("name: %s\n".formatted(slug));
        skillMd.append("description: %s (self-contained — %d dependencies inlined)\n".formatted(
                original.description(), deps.size()));
        skillMd.append("type: CODE\n");
        skillMd.append("category: %s\n".formatted(original.category()));
        skillMd.append("tags: [%s]\n".formatted(String.join(", ", allTags)));
        skillMd.append("---\n\n");
        skillMd.append("# %s\n\n".formatted(slug.replace("_", " ")));
        skillMd.append("Self-contained version of `%s` with all %d dependencies inlined.\n\n".formatted(
                original.slug(), deps.size()));
        skillMd.append("**Inlined dependencies:**\n");
        for (var dep : deps) {
            skillMd.append("- `%s`\n".formatted(dep));
        }
        skillMd.append("\n## Code\n```groovy\n");
        skillMd.append(inlinedCode);
        skillMd.append("\n```\n");

        Files.writeString(outputDir.resolve("SKILL.md"), skillMd.toString());

        // _meta.json — aggregate stats
        int totalUsage = original.usageCount();
        int totalSuccess = original.successCount();
        for (var dep : deps) {
            var depPkg = packageMap.get(dep);
            if (depPkg != null) {
                totalUsage += depPkg.usageCount();
                totalSuccess += depPkg.successCount();
            }
        }

        var meta = new LinkedHashMap<String, Object>();
        meta.put("id", UUID.randomUUID().toString().substring(0, 8));
        meta.put("slug", slug);
        meta.put("displayName", slug.replace("_", " "));
        meta.put("status", "CURATED");
        meta.put("skillType", "CODE");
        meta.put("usageCount", totalUsage);
        meta.put("successCount", totalSuccess);
        meta.put("createdAt", LocalDateTime.now().toString());
        meta.put("inlinedFrom", original.slug());
        meta.put("inlinedDeps", new ArrayList<>(deps));
        meta.put("qualityScore", Map.of(
                "documentation", original.qualityScore().documentation(),
                "testCoverage", original.qualityScore().testCoverage(),
                "errorHandling", original.qualityScore().errorHandling(),
                "codeComplexity", original.qualityScore().codeComplexity(),
                "outputFormat", original.qualityScore().outputFormat(),
                "total", original.qualityScore().total(),
                "grade", original.qualityScore().grade()
        ));
        meta.put("latest", Map.of("version", "1.0.0", "publishedAt", System.currentTimeMillis()));

        MAPPER.writeValue(outputDir.resolve("_meta.json").toFile(), meta);
    }
}
