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

Skills are graded on a 100-point scale across five dimensions. Each dimension tests a different aspect of skill quality:

| Dimension | Max Score | What It Measures |
|-----------|-----------|-----------------|
| Execution Test | 25 | Does the code actually compile and run? |
| Runtime Effectiveness | 25 | How well does it perform in real-world usage? |
| Code Quality | 20 | Is the code well-structured and safe? |
| Test Coverage | 15 | Are there examples and expected outputs? |
| Uniqueness | 15 | Does it bring something new to the table? |

### Dimension Breakdown

#### 1. Execution Test (0-25 points)

The skill's Groovy code is put through four checks:

- **Compilation (8 pts)** -- The code is parsed by the Groovy compiler. Syntax errors fail immediately with 0 points.
- **Sandbox Run (9 pts)** -- The code is executed in a sandboxed GroovyShell with mock tool responses and realistic parameters. Must complete within 5 seconds.
- **Output Quality (4 pts)** -- The return value is inspected: it must be non-null, longer than 20 characters, and not start with "ERROR".
- **Error Path Handling (4 pts)** -- The code is run again with empty parameters. A skill that returns a graceful error message scores full marks; one that crashes scores zero.

#### 2. Runtime Effectiveness (0-25 points)

Based on historical usage data from `_meta.json`:

| Success Rate | Base Score |
|-------------|-----------|
| >= 90% | 20 |
| >= 75% | 15 |
| >= 50% | 8 |
| < 50% | 3 |
| No usage data | 0 |

A volume bonus (up to +5) is awarded to skills with high usage counts (20+ uses), but only if the success rate is at least 70%. A skill that has been used 50 times with a 40% success rate gets no bonus -- it is unreliable.

#### 3. Code Quality (0-20 points)

A combination of the skill's existing quality score and additional static analysis:

- **Existing quality score (0-8 pts)** -- Mapped from the skill's pre-computed documentation, error handling, complexity, and output format scores.
- **Parameter validation (3 pts)** -- Does the code check for missing or null input parameters before proceeding?
- **Error handling (3 pts)** -- Are there try/catch blocks, null-safe operators (`?.`, `?:`), or other defensive patterns?
- **Structured output (2 pts)** -- Does the code produce formatted output (string building, template interpolation)?
- **Code size (2 pts)** -- Skills between 10-80 lines score full marks. Trivially short (<5 lines) or excessively long (>120 lines) code is penalized.
- **No hardcoded secrets (2 pts)** -- Penalty if `api_key`, `apiKey`, or `secret` patterns are found in the code.

#### 4. Test Coverage (0-15 points)

The skill's `SKILL.md` is analyzed for examples and test cases:

- **Example count (0-7 pts)** -- At least 2 examples are needed for a meaningful score. Three or more examples earn 7 points.
- **Assertions (4 pts)** -- Bonus for explicit assertions (`assert`, `assertEquals`, `should`, `expect`).
- **Expected output template (4 pts)** -- Bonus if the skill defines what correct output looks like.

#### 5. Uniqueness (0-15 points)

Measures how distinct a skill is from others in its capability group:

- **Jaccard similarity** is computed between the skill's feature set (category, tags, code patterns, name tokens) and every other skill in its group.
- A skill that is the only one in its group gets full 15 points.
- A skill with >80% similarity to a higher-ranked peer gets 0 points (it is redundant).
- Intermediate similarity scores map to 5 or 10 points.

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
