package ai.intelliswarm.curator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Summary of a full curation run.
 */
public record CurationReport(
        int totalAssessed,
        int totalPassed,
        int totalFailed,
        int totalPublished,
        int totalArchived,
        int groupsIdentified,
        Map<String, List<SkillAssessment>> rankedGroups,
        LocalDateTime curatedAt
) {
    public String toSummary() {
        var sb = new StringBuilder();
        sb.append("=== Curation Report ===\n");
        sb.append("Quality bar: %d/100 minimum for publication\n".formatted(SkillAssessor.PUBLICATION_BAR));
        sb.append("Assessed:  %d skills\n".formatted(totalAssessed));
        sb.append("Groups:    %d\n\n".formatted(groupsIdentified));

        // Collect candidates and rejects
        var candidates = new ArrayList<SkillAssessment>();
        var rejects = new ArrayList<SkillAssessment>();

        for (var entry : rankedGroups.entrySet()) {
            sb.append("--- %s ---\n".formatted(entry.getKey()));
            for (var a : entry.getValue()) {
                var status = a.passesCurationBar() ? "PUBLISH" : "REJECT";
                var icon = a.passesCurationBar() ? "+" : "x";
                sb.append("  [%s] #%d  %-45s  %3d/100 (%s)\n".formatted(
                        icon, a.rankInGroup(), a.skill().slug(),
                        a.totalScore(), a.grade()));

                if (a.passesCurationBar()) candidates.add(a);
                else rejects.add(a);
            }
        }

        // Publication candidates summary
        sb.append("\n========================================\n");
        sb.append("  PUBLICATION CANDIDATES (%d skills)\n".formatted(candidates.size()));
        sb.append("========================================\n");
        candidates.sort(Comparator.comparingInt(SkillAssessment::totalScore).reversed());
        for (var a : candidates) {
            sb.append("  %-45s  %3d/100 (%s)  [%s]\n".formatted(
                    a.skill().slug(), a.totalScore(), a.grade(), a.groupName()));
        }

        // Rejected skills with reasons
        if (!rejects.isEmpty()) {
            sb.append("\n========================================\n");
            sb.append("  REJECTED (%d skills) — not published\n".formatted(rejects.size()));
            sb.append("========================================\n");
            for (var a : rejects) {
                sb.append("  %-45s  %3d/100 (%s)\n".formatted(
                        a.skill().slug(), a.totalScore(), a.grade()));
                // Show top reasons for rejection
                for (var note : a.assessmentNotes()) {
                    if (note.contains("FAIL") || note.contains("WARN")
                            || note.contains("0/") || note.contains("crashed")
                            || note.contains("no param") || note.contains("no error")) {
                        sb.append("    - %s\n".formatted(note));
                    }
                }
            }
        }

        // Deletion candidates
        var deletionCandidates = rejects.stream()
                .filter(a -> a.totalScore() < 50)
                .toList();
        if (!deletionCandidates.isEmpty()) {
            sb.append("\n  DELETION CANDIDATES (score < 50):\n");
            for (var a : deletionCandidates) {
                sb.append("    DELETE → %s (%d/100)\n".formatted(a.skill().slug(), a.totalScore()));
            }
        }

        sb.append("\nPublished: %d | Archived: %d | Failed: %d\n".formatted(
                totalPublished, totalArchived, totalFailed));

        return sb.toString();
    }
}
