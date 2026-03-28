package ai.intelliswarm.curator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "curator", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Assess, rank, and curate AI agent skills.")
public class CuratorCli implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Option(names = {"--skills-dir", "-d"},
            description = "Directory containing skill packages (default: current dir)",
            defaultValue = ".")
    private Path skillsDir;

    @Option(names = {"--top-k", "-k"},
            description = "Number of skills to keep per group (default: 3)",
            defaultValue = "3")
    private int topK;

    @Option(names = {"--output-dir", "-o"},
            description = "Directory for CATALOG.md output (default: parent of skills-dir)")
    private Path outputDir;

    @Option(names = {"--inline-deps"},
            description = "Try to inline dependencies for skills that fail self-containment")
    private boolean inlineDeps;

    @Option(names = {"--live-test"},
            description = "Run skills against real APIs — no mocks. Only verified skills are trusted.")
    private boolean liveTest;

    @Option(names = {"--test-ticker"},
            description = "Ticker symbol for live testing financial skills (default: AAPL)",
            defaultValue = "AAPL")
    private String testTicker;

    @Option(names = {"--merge"},
            description = "Merge overlapping skills in large groups into super-skills")
    private boolean merge;

    @Option(names = {"--merge-min-group"},
            description = "Minimum group size to trigger merge (default: 3)",
            defaultValue = "3")
    private int mergeMinGroup;

    @Option(names = {"--commit"},
            description = "Git commit only the published (passing) skills")
    private boolean commit;

    @Option(names = {"--dry-run"},
            description = "Assess and report only — don't archive or move files")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            System.out.println("Skill Curator v1.0.0");
            var absSkillsDir = skillsDir.toAbsolutePath();
            var absOutputDir = outputDir != null ? outputDir.toAbsolutePath()
                    : absSkillsDir.getParent();
            System.out.printf("Skills dir:  %s%n", absSkillsDir);
            System.out.printf("Output dir:  %s%n", absOutputDir);
            System.out.printf("Top-K: %d per group%n%n", topK);

            var curator = new SkillCurator()
                    .withInlineDeps(inlineDeps && !dryRun);

            if (dryRun) {
                System.out.println("[DRY RUN] — no files will be moved or archived\n");
            }

            var report = curator.curate(absSkillsDir, absOutputDir, topK);

            // Live test published skills against real APIs
            if (liveTest) {
                System.out.println("\n========================================");
                System.out.println("  LIVE TESTING — real APIs, no mocks");
                System.out.println("========================================");
                System.out.printf("Test ticker: %s%n%n", testTicker);

                // Collect published skills
                var published = new ArrayList<SkillAssessment>();
                for (var group : report.rankedGroups().values()) {
                    for (int i = 0; i < Math.min(topK, group.size()); i++) {
                        if (group.get(i).passesCurationBar()) {
                            published.add(group.get(i));
                        }
                    }
                }

                var testParams = new HashMap<String, Object>();
                testParams.put("ticker", testTicker);
                testParams.put("symbol", testTicker);
                testParams.put("query", testTicker + " financial data");
                testParams.put("windowDays", 7);

                var runner = new LiveTestRunner();
                var results = runner.runLiveTests(published, testParams);

                // Summary
                int verified = 0, failed = 0;
                for (var r : results) {
                    if (r.passed()) verified++;
                    else failed++;
                }

                System.out.printf("%n--- Live Test Summary ---%n");
                System.out.printf("Verified: %d | Failed: %d | Total: %d%n",
                        verified, failed, results.size());

                // Write badges + archive failed skills
                if (!dryRun) {
                    var archivedDir = absSkillsDir.resolve("_archived");
                    Files.createDirectories(archivedDir);

                    for (var r : results) {
                        writeVerifiedBadge(absSkillsDir, r);
                        if (!r.passed()) {
                            var skillDir = absSkillsDir.resolve(r.slug());
                            var target = archivedDir.resolve(r.slug());
                            if (Files.exists(skillDir) && !Files.exists(target)) {
                                Files.move(skillDir, target);
                            }
                        }
                    }
                    System.out.printf("%nArchived %d unverified skills to _archived/%n", failed);

                    // Regenerate CATALOG.md with only verified skills
                    var verifiedSlugs = new HashSet<String>();
                    for (var r : results) {
                        if (r.passed()) verifiedSlugs.add(r.slug());
                    }
                    regenerateCatalog(verifiedSlugs, published, results, absOutputDir);
                }

                // Print verified skills
                if (verified > 0) {
                    System.out.println("\nVERIFIED SKILLS (real API data confirmed):");
                    for (var r : results) {
                        if (r.passed()) {
                            System.out.printf("  [VERIFIED] %-40s %dms, %d chars%n",
                                    r.slug(), r.executionTimeMs(), r.outputLength());
                        }
                    }
                }
                if (failed > 0) {
                    System.out.println("\nFAILED LIVE TEST:");
                    for (var r : results) {
                        if (!r.passed()) {
                            System.out.printf("  [FAILED]   %-40s %s%n", r.slug(), r.error());
                            for (var note : r.notes()) {
                                System.out.printf("             %s%n", note);
                            }
                        }
                    }
                }
            }

            // Merge overlapping skills
            if (merge && !dryRun) {
                System.out.println("\n--- Merging overlapping skills ---");
                var merger = new SkillMerger();
                var mergeResults = merger.mergeOverlapping(
                        report.rankedGroups(), absSkillsDir, mergeMinGroup);
                if (mergeResults.isEmpty()) {
                    System.out.println("No groups qualified for merging.");
                } else {
                    System.out.printf("Created %d merged super-skills in skills/_merged/%n",
                            mergeResults.size());
                    for (var r : mergeResults) {
                        System.out.printf("  %s <- %s%n", r.mergedSlug(),
                                String.join(" + ", r.sourceSkills()));
                    }
                }
            }

            System.out.printf("%nCATALOG.md written to %s%n",
                    absOutputDir.resolve("CATALOG.md"));

            // Selective git commit
            if (commit && !dryRun) {
                System.out.println("\n--- Git commit ---");
                var published = new ArrayList<SkillAssessment>();
                for (var group : report.rankedGroups().values()) {
                    for (int i = 0; i < Math.min(topK, group.size()); i++) {
                        if (group.get(i).passesCurationBar()) {
                            published.add(group.get(i));
                        }
                    }
                }
                new GitPublisher().commitPublished(published, absSkillsDir, absOutputDir);
            }

            return 0;

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private void writeVerifiedBadge(Path skillsDir, LiveTestRunner.LiveTestResult result) {
        try {
            var assessmentFile = skillsDir.resolve(result.slug()).resolve("_assessment.json");
            if (!Files.exists(assessmentFile)) return;

            var existing = MAPPER.readValue(assessmentFile.toFile(), Map.class);
            var updated = new LinkedHashMap<>(existing);
            updated.put("liveTest", Map.of(
                    "passed", result.passed(),
                    "verified", result.passed(),
                    "testedAt", LocalDateTime.now().toString(),
                    "executionTimeMs", result.executionTimeMs(),
                    "outputLength", result.outputLength(),
                    "outputMatchesTemplate", result.outputMatchesTemplate(),
                    "containsRealData", result.containsRealData(),
                    "error", result.error() != null ? result.error() : "",
                    "notes", result.notes()
            ));

            MAPPER.writeValue(assessmentFile.toFile(), updated);
        } catch (Exception e) {
            System.err.printf("WARN: Could not write verified badge for %s: %s%n",
                    result.slug(), e.getMessage());
        }
    }

    private void regenerateCatalog(Set<String> verifiedSlugs,
                                    List<SkillAssessment> allPublished,
                                    List<LiveTestRunner.LiveTestResult> liveResults,
                                    Path outputDir) {
        try {
            var sb = new StringBuilder();
            sb.append("# Curated Skills Catalog\n\n");
            sb.append("> Auto-generated by SkillCurator on %s\n\n".formatted(LocalDateTime.now()));

            // Build live test lookup
            var liveMap = new HashMap<String, LiveTestRunner.LiveTestResult>();
            for (var r : liveResults) liveMap.put(r.slug(), r);

            var verified = allPublished.stream()
                    .filter(a -> verifiedSlugs.contains(a.skill().slug()))
                    .sorted(Comparator.comparingInt(SkillAssessment::totalScore).reversed())
                    .toList();

            var failed = allPublished.stream()
                    .filter(a -> !verifiedSlugs.contains(a.skill().slug()))
                    .sorted(Comparator.comparingInt(SkillAssessment::totalScore).reversed())
                    .toList();

            sb.append("**Verified:** %d | **Archived:** %d\n\n".formatted(
                    verified.size(), failed.size()));

            // Verified skills table
            sb.append("## Verified Skills\n\n");
            sb.append("| Rank | Skill | Score | Grade | Live Test | Response Time | Output Size |\n");
            sb.append("|------|-------|-------|-------|-----------|--------------|-------------|\n");

            int rank = 1;
            for (var a : verified) {
                var lr = liveMap.get(a.skill().slug());
                sb.append("| %d | [%s](skills/%s/SKILL.md) | %d/100 | %s | VERIFIED | %dms | %d chars |\n"
                        .formatted(rank++, a.skill().slug(), a.skill().slug(),
                                a.totalScore(), a.grade(),
                                lr != null ? lr.executionTimeMs() : 0,
                                lr != null ? lr.outputLength() : 0));
            }

            // Archived (failed live test)
            if (!failed.isEmpty()) {
                sb.append("\n## Archived (failed live test)\n\n");
                sb.append("Moved to `skills/_archived/`. Passed structural quality gate but failed live API verification.\n\n");
                for (var a : failed) {
                    var lr = liveMap.get(a.skill().slug());
                    var reason = lr != null && lr.error() != null ? lr.error() : "no real data returned";
                    sb.append("- ~~%s~~ — %d/100 (%s) — %s\n".formatted(
                            a.skill().slug(), a.totalScore(), a.grade(), reason));
                }
            }

            Files.writeString(outputDir.resolve("CATALOG.md"), sb.toString());
            System.out.println("\nCATALOG.md regenerated with only verified skills.");
        } catch (Exception e) {
            System.err.println("WARN: Could not regenerate CATALOG.md: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CuratorCli()).execute(args);
        System.exit(exitCode);
    }
}
