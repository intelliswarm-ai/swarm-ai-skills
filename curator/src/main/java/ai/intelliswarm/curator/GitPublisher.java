package ai.intelliswarm.curator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Selectively stages and commits only published (passing) skills.
 * Archived skills are excluded from the commit.
 */
public class GitPublisher {

    /**
     * Stage published skills + CATALOG.md, commit with curation summary.
     * Does NOT push — the user decides when to push.
     *
     * @param published  assessments that passed curation
     * @param skillsDir  the skills/ directory
     * @param outputDir  where CATALOG.md lives (repo root)
     * @return true if commit succeeded
     */
    public boolean commitPublished(List<SkillAssessment> published,
                                    Path skillsDir, Path outputDir) throws IOException {
        if (published.isEmpty()) {
            System.out.println("No skills passed curation — nothing to commit.");
            return false;
        }

        var repoRoot = findGitRoot(outputDir);
        if (repoRoot == null) {
            System.err.println("ERROR: Not a git repository. Cannot commit.");
            return false;
        }

        // 1. Stage CATALOG.md
        git(repoRoot, "add", outputDir.resolve("CATALOG.md").toString());

        // 2. Stage each published skill directory
        for (var a : published) {
            var skillDir = a.skill().directory();
            git(repoRoot, "add", skillDir.toString());
        }

        // 3. Stage _archived directory (so removal is tracked)
        var archivedDir = skillsDir.resolve("_archived");
        if (archivedDir.toFile().exists()) {
            git(repoRoot, "add", archivedDir.toString());
        }

        // 4. Build commit message
        var msg = buildCommitMessage(published);

        // 5. Commit
        var exitCode = git(repoRoot, "commit", "-m", msg);
        if (exitCode == 0) {
            System.out.printf("Committed %d published skills.%n", published.size());
            System.out.println("Run 'git push' when ready to publish to remote.");
            return true;
        } else {
            System.err.println("Git commit failed (exit " + exitCode + ")");
            return false;
        }
    }

    private String buildCommitMessage(List<SkillAssessment> published) {
        var sb = new StringBuilder();
        sb.append("curate: publish %d skills after assessment\n\n".formatted(published.size()));
        sb.append("Skills published (score >= 60, top-K per group):\n");

        for (var a : published) {
            sb.append("  - %s (%d/100, %s) [%s #%d]\n".formatted(
                    a.skill().slug(), a.totalScore(), a.grade(),
                    a.groupName(), a.rankInGroup()));
        }

        sb.append("\nAssessed by SkillCurator v1.0.0");
        return sb.toString();
    }

    private Path findGitRoot(Path from) {
        var dir = from.toAbsolutePath();
        while (dir != null) {
            if (dir.resolve(".git").toFile().exists()) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    private int git(Path workDir, String... args) throws IOException {
        var cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);

        try {
            var process = new ProcessBuilder(cmd)
                    .directory(workDir.toFile())
                    .inheritIO()
                    .start();
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }
}
