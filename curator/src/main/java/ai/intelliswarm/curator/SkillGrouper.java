package ai.intelliswarm.curator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups skills by capability: primary grouping by category, then uses
 * Jaccard similarity on tags/code patterns to split overly broad categories
 * into sub-groups. Stack-ranks within each group by total score.
 */
public class SkillGrouper {

    private static final double SPLIT_THRESHOLD = 0.25;

    /**
     * Group assessments by capability.
     * Phase 1: Group by category.
     * Phase 2: Within large categories, split into sub-groups using Jaccard similarity.
     * Returns groups keyed by group name, each list sorted by totalScore descending.
     */
    public Map<String, List<SkillAssessment>> groupAndRank(List<SkillAssessment> assessments) {
        // Phase 1: Group by category
        var byCategory = new LinkedHashMap<String, List<SkillAssessment>>();
        for (var a : assessments) {
            byCategory.computeIfAbsent(a.skill().category(), k -> new ArrayList<>()).add(a);
        }

        // Phase 2: Split large categories using Jaccard similarity on feature sets
        var featureSets = new LinkedHashMap<String, Set<String>>();
        for (var a : assessments) {
            featureSets.put(a.skill().slug(), buildFeatureSet(a.skill()));
        }

        var result = new LinkedHashMap<String, List<SkillAssessment>>();

        for (var entry : byCategory.entrySet()) {
            var category = entry.getKey();
            var members = entry.getValue();

            if (members.size() <= 4) {
                // Small group — keep as-is
                var sorted = members.stream()
                        .sorted(Comparator.comparingInt(SkillAssessment::totalScore).reversed())
                        .toList();
                result.put(category, sorted);
            } else {
                // Large group — try to split into sub-groups
                var subGroups = splitByJaccard(members, featureSets, category);
                result.putAll(subGroups);
            }
        }
        return result;
    }

    /**
     * Split a large category into sub-groups using agglomerative clustering.
     */
    private Map<String, List<SkillAssessment>> splitByJaccard(
            List<SkillAssessment> members,
            Map<String, Set<String>> featureSets,
            String categoryName) {

        var subGroups = new LinkedHashMap<String, List<SkillAssessment>>();

        for (var a : members) {
            var features = featureSets.get(a.skill().slug());
            String bestGroup = null;
            double bestAvgSim = 0;

            // Find best matching sub-group by average similarity
            for (var entry : subGroups.entrySet()) {
                double avgSim = entry.getValue().stream()
                        .mapToDouble(m -> jaccard(features, featureSets.get(m.skill().slug())))
                        .average().orElse(0);
                if (avgSim > bestAvgSim) {
                    bestAvgSim = avgSim;
                    bestGroup = entry.getKey();
                }
            }

            if (bestAvgSim >= SPLIT_THRESHOLD && bestGroup != null) {
                subGroups.get(bestGroup).add(a);
            } else {
                // New sub-group — name from primary capability tokens
                var subName = deriveSubGroupName(a, categoryName, subGroups.keySet());
                subGroups.computeIfAbsent(subName, k -> new ArrayList<>()).add(a);
            }
        }

        // Sort each sub-group by score
        var sorted = new LinkedHashMap<String, List<SkillAssessment>>();
        for (var entry : subGroups.entrySet()) {
            sorted.put(entry.getKey(), entry.getValue().stream()
                    .sorted(Comparator.comparingInt(SkillAssessment::totalScore).reversed())
                    .toList());
        }
        return sorted;
    }

    private String deriveSubGroupName(SkillAssessment a, String category,
                                       Set<String> existingNames) {
        // Try to name from the skill's primary capability
        var slug = a.skill().slug();
        var tokens = slug.split("_");
        var name = category;

        // Pick the most descriptive 2-3 tokens
        var descriptive = Arrays.stream(tokens)
                .filter(t -> t.length() > 3)
                .filter(t -> !t.equals("data") && !t.equals("tool") && !t.equals("financial"))
                .limit(2)
                .collect(Collectors.joining("-"));

        if (!descriptive.isEmpty()) {
            name = category + ": " + descriptive;
        }

        // Ensure unique
        if (existingNames.contains(name)) {
            name = name + " (" + slug.substring(0, Math.min(8, slug.length())) + ")";
        }
        return name;
    }

    /**
     * Compute uniqueness score (0-15) for a skill within its group.
     */
    public int computeUniqueness(SkillPackage skill, List<SkillAssessment> groupMembers,
                                  Map<String, Set<String>> featureSets) {
        if (groupMembers.size() <= 1) return 15; // only member

        var myFeatures = featureSets.computeIfAbsent(skill.slug(), k -> buildFeatureSet(skill));
        double maxSimilarity = 0;

        for (var other : groupMembers) {
            if (other.skill().slug().equals(skill.slug())) continue;
            var otherFeatures = featureSets.computeIfAbsent(
                    other.skill().slug(), k -> buildFeatureSet(other.skill()));
            double sim = jaccard(myFeatures, otherFeatures);
            maxSimilarity = Math.max(maxSimilarity, sim);
        }

        // High similarity with any peer → low uniqueness
        if (maxSimilarity > 0.80) return 0;
        if (maxSimilarity > 0.60) return 5;
        if (maxSimilarity > 0.40) return 10;
        return 15;
    }

    Set<String> buildFeatureSet(SkillPackage skill) {
        var features = new HashSet<String>();
        features.add("cat:" + skill.category());
        skill.tags().forEach(t -> features.add("tag:" + t.toLowerCase()));

        // Add name tokens (split on _ and common words)
        for (var token : skill.slug().split("_")) {
            if (token.length() > 2) features.add("name:" + token.toLowerCase());
        }

        // Add key code patterns
        var code = skill.groovyCode().toLowerCase();
        if (code.contains("yahoo")) features.add("src:yahoo");
        if (code.contains("http") || code.contains("url")) features.add("src:http");
        if (code.contains("json")) features.add("fmt:json");
        if (code.contains("tools.")) features.add("pat:tool-composition");
        if (code.contains("fallback") || code.contains("catch")) features.add("pat:fallback");
        if (code.contains("ratio") || code.contains("calculator")) features.add("pat:calculation");

        return features;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        var intersection = new HashSet<>(a);
        intersection.retainAll(b);
        var union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
