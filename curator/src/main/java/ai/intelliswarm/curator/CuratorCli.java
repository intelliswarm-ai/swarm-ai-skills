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

    @Option(names = {"--llm-judge"},
            description = "Run LLM-as-judge evaluation on published skills (requires curator.yaml + .env)")
    private boolean llmJudge;

    @Option(names = {"--config", "-c"},
            description = "Path to curator.yaml config file (default: ./curator.yaml)",
            defaultValue = "curator.yaml")
    private Path configFile;

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

            // LLM-as-judge evaluation
            if (llmJudge) {
                System.out.println("\n========================================");
                System.out.println("  LLM-AS-JUDGE EVALUATION");
                System.out.println("========================================");

                try {
                    var absConfigFile = configFile.toAbsolutePath();
                    var judgeConfig = LlmJudgeConfig.load(absConfigFile);
                    System.out.printf("Provider: %s | Model: %s%n", judgeConfig.provider(), judgeConfig.model());
                    System.out.printf("Temperature: %.1f | Max tokens: %d%n%n",
                            judgeConfig.temperature(), judgeConfig.maxTokens());

                    var judge = new LlmJudge(judgeConfig);

                    // Collect published skills for evaluation
                    var publishedForJudge = new ArrayList<SkillAssessment>();
                    for (var group : report.rankedGroups().values()) {
                        for (int i = 0; i < Math.min(topK, group.size()); i++) {
                            if (group.get(i).passesCurationBar()) {
                                publishedForJudge.add(group.get(i));
                            }
                        }
                    }

                    // Collect live test results if available
                    List<LiveTestRunner.LiveTestResult> liveResultsForJudge = liveTest ? collectLiveResults(
                            publishedForJudge, report, absSkillsDir) : null;

                    var evaluations = judge.evaluateAll(publishedForJudge, liveResultsForJudge);

                    // Print summary
                    System.out.println("\n--- LLM Judge Summary ---");
                    for (var eval : evaluations) {
                        System.out.printf("  %-40s  %3d/100 (%s)  \"%s\"%n",
                                eval.skillSlug(), eval.overallScore(), eval.gradeFor(), eval.verdict());
                        System.out.printf("    Definition: %d/10  API Calls: %d/10  Response: %d/10  Output: %d/10  Useful: %d/10%n",
                                eval.toolDefinitionAccuracy(), eval.apiCallQuality(),
                                eval.responseHandling(), eval.outputQuality(), eval.usefulnessScore());
                        if (!eval.strengths().isEmpty()) {
                            System.out.printf("    Strengths: %s%n", String.join("; ", eval.strengths()));
                        }
                        if (!eval.weaknesses().isEmpty()) {
                            System.out.printf("    Weaknesses: %s%n", String.join("; ", eval.weaknesses()));
                        }
                    }

                    // Write evaluations to _assessment.json
                    if (!dryRun) {
                        for (var eval : evaluations) {
                            writeLlmEvaluation(absSkillsDir, eval);
                        }
                        System.out.printf("%nLLM evaluations written to _assessment.json for %d skills%n",
                                evaluations.size());

                        // Write detailed evaluation reports
                        for (var eval : evaluations) {
                            writeLlmReport(absSkillsDir, eval);
                        }
                        System.out.println("Detailed evaluation reports written to _llm_evaluation.md");
                    }

                } catch (Exception e) {
                    System.err.println("LLM Judge ERROR: " + e.getMessage());
                    e.printStackTrace();
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
            var liveTestData = new LinkedHashMap<String, Object>();
            liveTestData.put("passed", result.passed());
            liveTestData.put("verified", result.passed());
            liveTestData.put("testedAt", LocalDateTime.now().toString());
            liveTestData.put("executionTimeMs", result.executionTimeMs());
            liveTestData.put("outputLength", result.outputLength());
            liveTestData.put("outputMatchesTemplate", result.outputMatchesTemplate());
            liveTestData.put("containsRealData", result.containsRealData());
            liveTestData.put("testParams", result.testParams());
            liveTestData.put("error", result.error() != null ? result.error() : "");
            liveTestData.put("notes", result.notes());

            // Include API call traces for transparency
            if (result.apiCallTraces() != null && !result.apiCallTraces().isEmpty()) {
                var traceMaps = new ArrayList<Map<String, Object>>();
                for (var trace : result.apiCallTraces()) {
                    traceMaps.add(Map.of(
                            "toolName", trace.toolName(),
                            "method", trace.method(),
                            "url", trace.url(),
                            "params", trace.params(),
                            "responseLength", trace.responseLength(),
                            "responseSnippet", trace.responseSnippet(),
                            "success", trace.success(),
                            "durationMs", trace.durationMs()
                    ));
                }
                liveTestData.put("apiCallTraces", traceMaps);
            }

            updated.put("liveTest", liveTestData);

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

    /**
     * Re-run live tests to collect results for LLM judge (if live test was also run).
     * In practice, we read the already-written _assessment.json liveTest data.
     */
    private List<LiveTestRunner.LiveTestResult> collectLiveResults(
            List<SkillAssessment> published, CurationReport report, Path skillsDir) {
        // Re-run live tests to get fresh results for the judge
        var testParams = new HashMap<String, Object>();
        testParams.put("ticker", testTicker);
        testParams.put("symbol", testTicker);
        testParams.put("query", testTicker + " financial data");
        testParams.put("windowDays", 7);

        System.out.println("  (Re-running live tests to capture API call traces for LLM judge)");
        var runner = new LiveTestRunner();
        return runner.runLiveTests(published, testParams);
    }

    @SuppressWarnings("unchecked")
    private void writeLlmEvaluation(Path skillsDir, LlmEvaluation eval) {
        try {
            var assessmentFile = skillsDir.resolve(eval.skillSlug()).resolve("_assessment.json");
            if (!Files.exists(assessmentFile)) return;

            var existing = MAPPER.readValue(assessmentFile.toFile(), Map.class);
            var updated = new LinkedHashMap<>(existing);
            updated.put("llmEvaluation", eval.toMap());

            MAPPER.writeValue(assessmentFile.toFile(), updated);
        } catch (Exception e) {
            System.err.printf("WARN: Could not write LLM evaluation for %s: %s%n",
                    eval.skillSlug(), e.getMessage());
        }
    }

    private void writeLlmReport(Path skillsDir, LlmEvaluation eval) {
        try {
            var reportFile = skillsDir.resolve(eval.skillSlug()).resolve("_llm_evaluation.md");

            var sb = new StringBuilder();
            sb.append("# LLM Evaluation Report: %s\n\n".formatted(eval.skillSlug()));
            sb.append("> Evaluated by **%s** on %s (%dms)\n\n".formatted(
                    eval.model(), eval.evaluatedAt(), eval.evaluationTimeMs()));

            sb.append("## Verdict\n\n");
            sb.append("**%s** — Overall Score: **%d/100** (Grade: %s)\n\n".formatted(
                    eval.verdict(), eval.overallScore(), eval.gradeFor()));

            sb.append("## Scores\n\n");
            sb.append("| Dimension | Score | Weight |\n");
            sb.append("|-----------|-------|--------|\n");
            sb.append("| Tool Definition Accuracy | %d/10 | 15%% |\n".formatted(eval.toolDefinitionAccuracy()));
            sb.append("| API Call Quality | %d/10 | 25%% |\n".formatted(eval.apiCallQuality()));
            sb.append("| Response Handling | %d/10 | 20%% |\n".formatted(eval.responseHandling()));
            sb.append("| Output Quality | %d/10 | 20%% |\n".formatted(eval.outputQuality()));
            sb.append("| Usefulness | %d/10 | 20%% |\n\n".formatted(eval.usefulnessScore()));

            sb.append("## Tool Definition Analysis\n\n%s\n\n".formatted(eval.definitionAnalysis()));
            sb.append("## API Call Analysis\n\n%s\n\n".formatted(eval.apiCallAnalysis()));
            sb.append("## Response Handling Analysis\n\n%s\n\n".formatted(eval.responseAnalysis()));
            sb.append("## Output Quality Analysis\n\n%s\n\n".formatted(eval.outputAnalysis()));
            sb.append("## Usefulness Analysis\n\n%s\n\n".formatted(eval.usefulnessAnalysis()));

            if (!eval.strengths().isEmpty()) {
                sb.append("## Strengths\n\n");
                for (var s : eval.strengths()) sb.append("- %s\n".formatted(s));
                sb.append("\n");
            }

            if (!eval.weaknesses().isEmpty()) {
                sb.append("## Weaknesses\n\n");
                for (var w : eval.weaknesses()) sb.append("- %s\n".formatted(w));
                sb.append("\n");
            }

            if (!eval.recommendations().isEmpty()) {
                sb.append("## Recommendations\n\n");
                for (int i = 0; i < eval.recommendations().size(); i++) {
                    sb.append("%d. %s\n".formatted(i + 1, eval.recommendations().get(i)));
                }
            }

            Files.writeString(reportFile, sb.toString());
        } catch (Exception e) {
            System.err.printf("WARN: Could not write LLM report for %s: %s%n",
                    eval.skillSlug(), e.getMessage());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CuratorCli()).execute(args);
        System.exit(exitCode);
    }
}
