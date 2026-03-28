package ai.intelliswarm.curator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans a directory for skill packages (dirs containing SKILL.md + _meta.json).
 */
public class SkillScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```groovy\\s*\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern FRONT_MATTER = Pattern.compile(
            "^---\\s*\\n(.*?)---\\s*\\n", Pattern.DOTALL);

    public List<SkillPackage> scan(Path skillsDir) throws IOException {
        var packages = new ArrayList<SkillPackage>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> !d.getFileName().toString().startsWith("_"))
                .filter(d -> !d.getFileName().toString().equals("curator"))
                .filter(d -> Files.exists(d.resolve("SKILL.md"))
                          && Files.exists(d.resolve("_meta.json")))
                .forEach(d -> {
                    try {
                        packages.add(loadPackage(d));
                    } catch (IOException e) {
                        System.err.println("WARN: skipping " + d.getFileName() + ": " + e.getMessage());
                    }
                });
        }
        return packages;
    }

    private SkillPackage loadPackage(Path dir) throws IOException {
        var metaJson = MAPPER.readTree(dir.resolve("_meta.json").toFile());
        var skillMd = Files.readString(dir.resolve("SKILL.md"));

        // Parse front matter for category, tags, description
        var frontMatter = parseFrontMatter(skillMd);
        var category = frontMatter.getOrDefault("category", "uncategorized");
        var tags = parseTags(frontMatter.getOrDefault("tags", ""));
        var description = frontMatter.getOrDefault("description", "");

        // Extract groovy code block
        var codeMatcher = CODE_BLOCK.matcher(skillMd);
        var groovyCode = codeMatcher.find() ? codeMatcher.group(1).trim() : "";

        // Parse _meta.json fields
        var slug = metaJson.path("slug").asText(dir.getFileName().toString());
        var displayName = metaJson.path("displayName").asText(slug);
        var status = metaJson.path("status").asText("ACTIVE");
        var skillType = metaJson.path("skillType").asText("CODE");
        var usageCount = metaJson.path("usageCount").asInt(0);
        var successCount = metaJson.path("successCount").asInt(0);
        var createdAtStr = metaJson.path("createdAt").asText("");
        var createdAt = createdAtStr.isEmpty() ? LocalDateTime.now()
                : LocalDateTime.parse(createdAtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        var version = "1.0.0";
        var latestNode = metaJson.path("latest");
        if (!latestNode.isMissingNode()) {
            version = latestNode.path("version").asText("1.0.0");
        }

        var qs = metaJson.path("qualityScore");
        var qualityScore = new SkillPackage.QualityScore(
                qs.path("documentation").asInt(0),
                qs.path("testCoverage").asInt(0),
                qs.path("errorHandling").asInt(0),
                qs.path("codeComplexity").asInt(0),
                qs.path("outputFormat").asInt(0),
                qs.path("total").asInt(0),
                qs.path("grade").asText("F")
        );

        return new SkillPackage(slug, displayName, description, category, tags,
                status, skillType, usageCount, successCount, groovyCode,
                qualityScore, version, createdAt, dir);
    }

    private Map<String, String> parseFrontMatter(String markdown) {
        var map = new HashMap<String, String>();
        var matcher = FRONT_MATTER.matcher(markdown);
        if (matcher.find()) {
            for (String line : matcher.group(1).split("\n")) {
                var colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    var key = line.substring(0, colonIdx).trim();
                    var value = line.substring(colonIdx + 1).trim();
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private List<String> parseTags(String tagsStr) {
        if (tagsStr.isBlank()) return List.of();
        // Handle [tag1, tag2, tag3] format
        var cleaned = tagsStr.replaceAll("[\\[\\]]", "").trim();
        if (cleaned.isEmpty()) return List.of();
        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
