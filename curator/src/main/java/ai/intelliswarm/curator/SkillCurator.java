package ai.intelliswarm.curator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Main orchestrator: scan → assess → inline deps → group → rank → prune → publish.
 */
public class SkillCurator {

    private final SkillScanner scanner = new SkillScanner();
    private final SkillAssessor assessor = new SkillAssessor();
    private final SkillGrouper grouper = new SkillGrouper();
    private final CatalogGenerator catalogGenerator = new CatalogGenerator();
    private final DependencyInliner inliner = new DependencyInliner();

    private boolean inlineDeps = false;

    public SkillCurator withInlineDeps(boolean inlineDeps) {
        this.inlineDeps = inlineDeps;
        return this;
    }

    public CurationReport curate(Path skillsDir, Path outputDir, int topK) throws IOException {
        // 1. Scan
        var packages = scanner.scan(skillsDir);
        System.out.printf("Scanned %d skill packages%n", packages.size());

        if (packages.isEmpty()) {
            return new CurationReport(0, 0, 0, 0, 0, 0, Map.of(), LocalDateTime.now());
        }

        // 2. Initial assessment (6 dimensions, uniqueness deferred)
        var initialAssessments = new ArrayList<SkillAssessment>();
        for (var pkg : packages) {
            initialAssessments.add(assessSingle(pkg));
        }

        // 3. Inline dependencies for skills that fail self-containment
        //    This happens BEFORE archiving, while all deps are still available
        if (inlineDeps) {
            var failedSelfContainment = initialAssessments.stream()
                    .filter(a -> a.selfContainmentScore() <= 5)
                    .toList();

            if (!failedSelfContainment.isEmpty()) {
                System.out.println("\n--- Inlining dependencies ---");
                var inlineResults = inliner.inlineFailedSkills(
                        failedSelfContainment, packages, skillsDir);

                // Scan the _inlined dir for new packages and assess them
                var inlinedDir = skillsDir.resolve("_inlined");
                if (inlinedDir.toFile().exists()) {
                    var inlinedPackages = scanner.scan(inlinedDir);
                    for (var inlinedPkg : inlinedPackages) {
                        packages.add(inlinedPkg);
                        initialAssessments.add(assessSingle(inlinedPkg));
                        System.out.printf("  Assessed inlined: %s%n", inlinedPkg.slug());
                    }
                }

                // Report failures
                for (var r : inlineResults) {
                    if (!r.success()) {
                        System.out.printf("  SKIP %s: %s%n", r.slug(), r.reason());
                    }
                }
            }
        }

        // 4. Group and rank
        var groups = grouper.groupAndRank(initialAssessments);
        System.out.printf("Identified %d capability groups%n", groups.size());

        // 5. Compute uniqueness within groups + finalize scores
        var featureSets = new HashMap<String, Set<String>>();
        for (var pkg : packages) {
            featureSets.put(pkg.slug(), grouper.buildFeatureSet(pkg));
        }

        var finalGroups = new LinkedHashMap<String, List<SkillAssessment>>();
        int totalPassed = 0, totalFailed = 0, totalPublished = 0, totalArchived = 0;

        for (var entry : groups.entrySet()) {
            var groupName = entry.getKey();
            var members = entry.getValue();
            var finalMembers = new ArrayList<SkillAssessment>();

            for (var a : members) {
                int uniqueness = grouper.computeUniqueness(a.skill(), members, featureSets);
                uniqueness = Math.min(10, (int) (uniqueness * (10.0 / 15)));

                int total = a.executionScore() + a.effectivenessScore()
                        + a.codeQualityScore() + a.testCoverageScore()
                        + a.selfContainmentScore() + a.universalityScore()
                        + uniqueness;
                var notes = new ArrayList<>(a.assessmentNotes());
                notes.add("Uniqueness: %d/10 (group size=%d)".formatted(uniqueness, members.size()));

                boolean passes = total >= SkillAssessor.PUBLICATION_BAR;
                var finalAssessment = new SkillAssessment(
                        a.skill(), a.executionScore(), a.effectivenessScore(),
                        a.codeQualityScore(), a.testCoverageScore(), uniqueness,
                        a.selfContainmentScore(), a.universalityScore(),
                        total, SkillAssessment.gradeFor(total), passes,
                        groupName, 0, notes);
                finalMembers.add(finalAssessment);
            }

            finalMembers.sort(Comparator.comparingInt(SkillAssessment::totalScore).reversed());

            var ranked = new ArrayList<SkillAssessment>();
            for (int i = 0; i < finalMembers.size(); i++) {
                var a = finalMembers.get(i);
                ranked.add(new SkillAssessment(
                        a.skill(), a.executionScore(), a.effectivenessScore(),
                        a.codeQualityScore(), a.testCoverageScore(), a.uniquenessScore(),
                        a.selfContainmentScore(), a.universalityScore(),
                        a.totalScore(), a.grade(), a.passesCurationBar(),
                        groupName, i + 1, a.assessmentNotes()));

                if (i < topK && a.passesCurationBar()) {
                    totalPublished++;
                    totalPassed++;
                } else {
                    totalArchived++;
                    if (a.passesCurationBar()) totalPassed++;
                    else totalFailed++;
                }
            }
            finalGroups.put(groupName, ranked);
        }

        // 6. Generate outputs
        catalogGenerator.generate(finalGroups, skillsDir, outputDir, topK);

        var report = new CurationReport(
                packages.size(), totalPassed, totalFailed,
                totalPublished, totalArchived,
                finalGroups.size(), finalGroups, LocalDateTime.now());

        System.out.println(report.toSummary());
        return report;
    }

    private SkillAssessment assessSingle(SkillPackage skill) {
        var notes = new ArrayList<String>();

        var execResult = assessor.assessExecution(skill);
        notes.addAll(execResult.notes());

        int effectiveness = assessor.assessEffectiveness(skill, notes);
        int codeQuality = assessor.assessCodeQuality(skill, notes);
        int testCoverage = assessor.assessTestCoverage(skill, notes);
        int selfContainment = assessor.assessSelfContainment(skill, notes);
        int universality = assessor.assessUniversality(skill, notes);

        int partialTotal = execResult.score() + effectiveness + codeQuality
                + testCoverage + selfContainment + universality;

        return new SkillAssessment(
                skill, execResult.score(), effectiveness, codeQuality,
                testCoverage, 0, selfContainment, universality,
                partialTotal, SkillAssessment.gradeFor(partialTotal),
                partialTotal >= SkillAssessor.PUBLICATION_BAR,
                "", 0, notes);
    }
}
