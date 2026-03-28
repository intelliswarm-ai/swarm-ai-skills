# swarm-ai-skills

A curated repository of AI agent skills for the [IntelliSwarm](https://github.com/intelliswarm-ai/swarm-ai) framework. Only skills that pass a strict, automated quality gate are published here.

## Repository Structure

```
swarm-ai-skills/
├── CATALOG.md              # Auto-generated ranked catalog of all published skills
├── skills/                 # Published skill packages (SKILL.md + _meta.json each)
│   ├── _archived/          # Skills that failed curation (kept for reference)
│   └── _merged/            # Merged super-skills combining overlapping capabilities
└── curator/                # The SkillCurator CLI tool (Java 21 + Groovy)
```

Each skill package contains:

| File | Purpose |
|------|---------|
| `SKILL.md` | Skill definition: description, Groovy code, examples, output template |
| `_meta.json` | Metadata: usage stats, quality scores, version history |
| `_assessment.json` | Curator's assessment: scores per dimension, grade, group rank |

## Skill Evaluation Process

Every skill goes through a rigorous, automated evaluation pipeline before it can be published. The process is designed to ensure that only high-quality, genuinely useful, and non-redundant skills make it into the repository.

### Quality Gate

**Minimum score for publication: 75/100**

Skills are graded on a 100-point scale across seven dimensions. Each dimension tests a different aspect of skill quality:

| Dimension | Max Score | What It Measures |
|-----------|-----------|-----------------|
| Execution Test | 20 | Does the code actually compile and run? |
| Runtime Effectiveness | 15 | How well does it perform in real-world usage? |
| Code Quality | 15 | Is the code well-structured and safe? |
| Test Coverage | 10 | Are there examples and expected outputs? |
| Uniqueness | 10 | Does it bring something new to the table? |
| **Self-Containment** | **15** | **Can it run without depending on other skills?** |
| **Portability** | **15** | **Is all config inline? Can you drop it in and it just works?** |

### Dimension Breakdown

#### 1. Execution Test (0-20 points)

The skill's Groovy code is put through four checks:

- **Compilation (6 pts)** -- The code is parsed by the Groovy compiler. Syntax errors fail immediately with 0 points.
- **Sandbox Run (7 pts)** -- The code is executed in a sandboxed GroovyShell with mock tool responses and realistic parameters. Must complete within 5 seconds.
- **Output Quality (4 pts)** -- The return value is inspected: it must be non-null, longer than 20 characters, and not start with "ERROR".
- **Error Path Handling (3 pts)** -- The code is run again with empty parameters. A skill that returns a graceful error message scores full marks; one that crashes scores zero.

#### 2. Runtime Effectiveness (0-15 points)

Based on historical usage data from `_meta.json`:

| Success Rate | Base Score |
|-------------|-----------|
| >= 90% | 12 |
| >= 75% | 9 |
| >= 50% | 5 |
| < 50% | 2 |
| No usage data | 0 |

A volume bonus (up to +3) is awarded to skills with high usage counts (20+ uses), but only if the success rate is at least 70%.

#### 3. Code Quality (0-15 points)

A combination of the skill's existing quality score and additional static analysis:

- **Existing quality score (0-6 pts)** -- Mapped from the skill's pre-computed documentation, error handling, complexity, and output format scores.
- **Parameter validation (2 pts)** -- Does the code check for missing or null input parameters before proceeding?
- **Error handling (2 pts)** -- Are there try/catch blocks, null-safe operators (`?.`, `?:`), or other defensive patterns?
- **Structured output (2 pts)** -- Does the code produce formatted output (string building, template interpolation)?
- **Code size (1 pt)** -- Skills between 10-80 lines score full marks.
- **No hardcoded secrets (2 pts)** -- Penalty if `api_key`, `apiKey`, or `secret` patterns are found in the code.

#### 4. Test Coverage (0-10 points)

The skill's `SKILL.md` is analyzed for examples and test cases:

- **Example count (0-4 pts)** -- At least 2 examples are needed for a meaningful score. Three or more examples earn 4 points.
- **Assertions (3 pts)** -- Bonus for explicit assertions (`assert`, `assertEquals`, `should`, `expect`).
- **Expected output template (3 pts)** -- Bonus if the skill defines what correct output looks like.

#### 5. Uniqueness (0-10 points)

Measures how distinct a skill is from others in its capability group:

- **Jaccard similarity** is computed between the skill's feature set (category, tags, code patterns, name tokens) and every other skill in its group.
- A skill that is the only one in its group gets full 10 points.
- A skill with >80% similarity to a higher-ranked peer gets 0 points (it is redundant).
- Intermediate similarity scores map to 3 or 7 points.

#### 6. Self-Containment (0-15 points)

Measures whether a skill can run standalone without depending on other skills in the registry. A skill that requires a chain of other skills to function is fragile and not portable.

The code is scanned for `tools.*` calls. Each call is classified as either an **infrastructure tool** (generic utilities like `http_request`, `web_search`, `web_content_extract`) or a **skill dependency** (another skill that must exist in the registry).

| Situation | Score |
|-----------|-------|
| No `tools.*` calls at all -- fully self-contained | 15 |
| Calls only infrastructure tools (HTTP, search) | 10 |
| Depends on 1-2 other skills | 5 |
| Deep dependency chain (3+ skill calls) | 0 |

#### 7. Portability (0-15 points)

Measures whether a skill is fully portable: can someone drop it into their environment and it just works? Domain-specific skills (finance, medical, legal) are perfectly fine -- what matters is that **all configuration, URLs, and resources needed to run are inside the skill itself**.

| Check | Points | What it looks for |
|-------|--------|-------------------|
| Inline URLs/endpoints | 3 | If the skill uses HTTP, are the URLs embedded in the code? Or does it assume external config? |
| Clear input contract | 3 | Are parameters declared, validated, and documented? |
| Clear output contract | 3 | Does the code have a structured return value? |
| Complete metadata | 3 | Description, category, tags -- is the skill well-described? |
| No external state | 3 | No env vars, no local file reads, no database dependencies |

A finance skill that has its Yahoo Finance URL inline, validates the `ticker` param, and returns structured output scores full marks. A "generic" skill that reads config from env vars and assumes a database connection scores poorly.

### Grading Scale

| Grade | Score Range | Outcome |
|-------|-----------|---------|
| A | 90-100 | Published -- exceptional quality |
| B | 80-89 | Published -- high quality |
| C | 75-79 | Published -- meets the bar |
| D | 60-74 | Archived -- below publication standard |
| F | 0-59 | Archived -- candidate for deletion |

### Grouping and Deduplication

Skills are grouped by capability to prevent redundancy:

1. **Primary grouping by category** -- Skills are first grouped by their declared category (e.g., `data-io`, `computation`, `analysis`).
2. **Sub-grouping by similarity** -- Large categories are split into sub-groups using Jaccard similarity on feature sets (tags, code patterns, name tokens). Skills with average similarity >= 0.25 to existing group members join that group.
3. **Stack-ranking** -- Within each group, skills are sorted by total score (descending).
4. **Top-K selection** -- Only the best K skills per group are published (default: 3). The rest are archived.

### Skill Merging

When multiple high-quality skills (score >= 70) in the same group address overlapping capabilities, the curator can merge them into a single super-skill:

- The highest-scoring skill becomes the **base** implementation.
- Lower-ranked skills are woven in as **fallback strategies** -- if the base fails, the merged skill automatically tries the next approach.
- A `_merge_manifest.json` records provenance: which source skills were merged, their scores, and the merge timestamp.
- Merged skills inherit the combined usage statistics and tags from all source skills.

## Running the Curator

```bash
# Build
cd curator && mvn package

# Evaluate all skills (preview only)
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --dry-run

# Full run: evaluate, archive losers, merge overlapping, generate CATALOG.md
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --merge

# Commit only published skills to git
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --merge --commit
```

### CLI Options

| Flag | Default | Description |
|------|---------|-------------|
| `--skills-dir`, `-d` | `.` | Directory containing skill packages |
| `--top-k`, `-k` | `3` | Number of skills to keep per group |
| `--output-dir`, `-o` | Parent of skills-dir | Where to write CATALOG.md |
| `--merge` | off | Merge overlapping skills into super-skills |
| `--merge-min-group` | `3` | Minimum group size to trigger merging |
| `--commit` | off | Git commit only published skills |
| `--dry-run` | off | Assess and report only, no file changes |

### Curator Output

The curator prints a clear report showing:

- **`[+]`** Publication candidates -- skills that passed the quality gate
- **`[x]`** Rejected skills -- with specific reasons for failure
- **Deletion candidates** -- skills scoring below 50 that should be removed entirely

## Tech Stack

- Java 21 (preview features enabled)
- Apache Groovy 4.0 (skill execution sandbox)
- Picocli (CLI framework)
- Jackson (JSON parsing)
- Maven (build + shade plugin for fat jar)
