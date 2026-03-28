package ai.intelliswarm.curator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main orchestrator: scan → assess → group → rank → prune → publish.
 */
public class SkillCurator {

    private final SkillScanner scanner = new SkillScanner();
    private final SkillAssessor assessor = new SkillAssessor();
    private final SkillGrouper grouper = new SkillGrouper();
    private final CatalogGenerator catalogGenerator = new CatalogGenerator();

    /**
     * Full curation pipeline.
     *
     * @param skillsDir directory containing skill packages
     * @param topK      how many to keep per group
     * @return the curation report
     */
    public CurationReport curate(Path skillsDir, Path outputDir, int topK) throws IOException {
        // 1. Scan
        var packages = scanner.scan(skillsDir);
        System.out.printf("Scanned %d skill packages%n", packages.size());

        if (packages.isEmpty()) {
            return new CurationReport(0, 0, 0, 0, 0, 0, Map.of(), LocalDateTime.now());
        }

        // 2. Initial assessment (4 dimensions, uniqueness deferred)
        var initialAssessments = new ArrayList<SkillAssessment>();
        for (var pkg : packages) {
            var assessment = assessSingle(pkg);
            initialAssessments.add(assessment);
        }

        // 3. Group and rank
        var groups = grouper.groupAndRank(initialAssessments);
        System.out.printf("Identified %d capability groups%n", groups.size());

        // 4. Compute uniqueness within groups + finalize scores
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
                int total = a.executionScore() + a.effectivenessScore()
                        + a.codeQualityScore() + a.testCoverageScore() + uniqueness;
                var notes = new ArrayList<>(a.assessmentNotes());
                notes.add("Uniqueness: %d/15 (group size=%d)".formatted(uniqueness, members.size()));

                boolean passes = total >= SkillAssessor.PUBLICATION_BAR;
                var finalAssessment = new SkillAssessment(
                        a.skill(), a.executionScore(), a.effectivenessScore(),
                        a.codeQualityScore(), a.testCoverageScore(), uniqueness,
                        total, SkillAssessment.gradeFor(total), passes,
                        groupName, 0, notes);
                finalMembers.add(finalAssessment);
            }

            // Re-sort by final total score
            finalMembers.sort(Comparator.comparingInt(SkillAssessment::totalScore).reversed());

            // Assign ranks
            var ranked = new ArrayList<SkillAssessment>();
            for (int i = 0; i < finalMembers.size(); i++) {
                var a = finalMembers.get(i);
                ranked.add(new SkillAssessment(
                        a.skill(), a.executionScore(), a.effectivenessScore(),
                        a.codeQualityScore(), a.testCoverageScore(), a.uniquenessScore(),
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

        // 5. Generate outputs
        catalogGenerator.generate(finalGroups, skillsDir, outputDir, topK);

        var report = new CurationReport(
                packages.size(), totalPassed, totalFailed,
                totalPublished, totalArchived,
                finalGroups.size(), finalGroups, LocalDateTime.now());

        System.out.println(report.toSummary());
        return report;
    }

    /**
     * Assess a single skill on 4 dimensions (uniqueness computed later in group context).
     */
    private SkillAssessment assessSingle(SkillPackage skill) {
        var notes = new ArrayList<String>();

        var execResult = assessor.assessExecution(skill);
        notes.addAll(execResult.notes());

        int effectiveness = assessor.assessEffectiveness(skill, notes);
        int codeQuality = assessor.assessCodeQuality(skill, notes);
        int testCoverage = assessor.assessTestCoverage(skill, notes);

        int partialTotal = execResult.score() + effectiveness + codeQuality + testCoverage;

        return new SkillAssessment(
                skill, execResult.score(), effectiveness, codeQuality,
                testCoverage, 0, partialTotal,
                SkillAssessment.gradeFor(partialTotal), partialTotal >= 60,
                "", 0, notes);
    }
}
