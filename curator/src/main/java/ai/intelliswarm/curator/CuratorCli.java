package ai.intelliswarm.curator;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;

@Command(name = "curator", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Assess, rank, and curate AI agent skills.")
public class CuratorCli implements Callable<Integer> {

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

            var curator = new SkillCurator();

            if (dryRun) {
                System.out.println("[DRY RUN] — no files will be moved or archived\n");
            }

            var report = curator.curate(absSkillsDir, absOutputDir, topK);

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
                        System.out.printf("  %s ← %s%n", r.mergedSlug(),
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CuratorCli()).execute(args);
        System.exit(exitCode);
    }
}
