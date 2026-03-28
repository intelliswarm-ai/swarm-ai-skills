# swarm-ai-skills

A curated repository of AI agent skills for the [IntelliSwarm](https://github.com/intelliswarm-ai/swarm-ai) framework.

Only skills that pass both a strict automated quality gate **and** live verification against real APIs are published here. Everything else is archived.

## Repository Structure

```
swarm-ai-skills/
├── CATALOG.md              # Auto-generated ranked catalog of verified skills
├── skills/                 # Only VERIFIED skill packages live here
│   └── _archived/          # Skills that failed curation or live testing
└── curator/                # The SkillCurator CLI tool (Java 21 + Groovy)
```

Each skill package contains:

| File | Purpose |
|------|---------|
| `SKILL.md` | Skill definition: description, Groovy code, examples, output template |
| `_meta.json` | Metadata: usage stats, quality scores, version history |
| `_assessment.json` | Curator's full assessment: scores, grade, group rank, live test results |

## How Skills Get Published

A skill must pass **two stages** before it appears in this repository.

### Stage 1: Automated Quality Gate (75/100 minimum)

Skills are scored on a 100-point scale across seven dimensions:

| Dimension | Max | What It Measures |
|-----------|-----|-----------------|
| Execution Test | 20 | Does the code compile, run in a sandbox, and handle errors gracefully? |
| Runtime Effectiveness | 15 | Historical success rate and usage volume from production runs |
| Code Quality | 15 | Parameter validation, error handling, structured output, no hardcoded secrets |
| Test Coverage | 10 | Examples, assertions, and expected output templates in SKILL.md |
| Uniqueness | 10 | How different is this skill from others in its group? (Jaccard similarity) |
| Self-Containment | 15 | Can it run standalone? Skills that depend on chains of other skills score 0 |
| Portability | 15 | Is all config inline? Clear input/output contract? No external state? |

Skills scoring below 75 are archived immediately. Domain-specific skills (finance, medical, etc.) are welcome as long as they are self-contained and portable.

### Stage 2: Live Verification (the real test)

Skills that pass Stage 1 are then tested against **real APIs with no mocks**:

1. The skill's Groovy code runs in a sandbox with real HTTP tools (not mock placeholders)
2. Infrastructure tools (`web_search`, `http_request`, `web_content_extract`) make actual network requests
3. The output is validated:
   - Must be non-null and longer than 50 characters
   - Must not start with "ERROR" or "No data"
   - Must contain real data signals (numbers, dollar amounts, percentages, structured fields)
   - Is checked against the expected output template from SKILL.md
4. Skills that return real, verifiable data earn the **VERIFIED** badge
5. Skills that fail live testing are moved to `_archived/`

**Only verified skills remain in `skills/`.** The `_assessment.json` for each verified skill contains the full live test record:

```json
{
  "liveTest": {
    "verified": true,
    "testedAt": "2026-03-28T18:28:33",
    "executionTimeMs": 224,
    "outputLength": 14348,
    "containsRealData": true,
    "outputMatchesTemplate": true
  }
}
```

## Evaluation Dimensions in Detail

### 1. Execution Test (0-20)

- **Compilation (6 pts)** -- Parsed by the Groovy compiler. Syntax errors = 0 points.
- **Sandbox Run (7 pts)** -- Executed in a sandboxed GroovyShell with mock tools and realistic parameters. Must complete within 5 seconds.
- **Output Quality (4 pts)** -- Return value must be non-null, > 20 characters, and not start with "ERROR".
- **Error Path (3 pts)** -- Re-run with empty parameters. Graceful error message = full marks; crash = 0.

### 2. Runtime Effectiveness (0-15)

Based on historical usage data from `_meta.json`:

| Success Rate | Score | Volume Bonus (if rate >= 70%) |
|-------------|-------|-------------------------------|
| >= 90% | 12 | +3 for 20+ uses, +2 for 10+, +1 for 5+ |
| >= 75% | 9 | same |
| >= 50% | 5 | none |
| < 50% | 2 | none |
| No usage | 0 | none |

### 3. Code Quality (0-15)

- **Existing quality score (0-6)** -- From pre-computed documentation, error handling, complexity scores.
- **Parameter validation (2)** -- Checks for missing/null params before proceeding.
- **Error handling (2)** -- try/catch, null-safe operators, defensive patterns.
- **Structured output (2)** -- Formatted return values (string building, template interpolation).
- **Code substance (2)** -- 40+ lines = full marks. More code = more logic = likely more useful.
- **No hardcoded secrets (2)** -- Penalty if `api_key`, `apiKey`, or `secret` found.

### 4. Test Coverage (0-10)

- **Examples (0-4)** -- 3+ examples = 4 pts, 2 = 3 pts, 1 = 1 pt.
- **Assertions (3)** -- Explicit `assert`, `assertEquals`, `should`, `expect` statements.
- **Expected output template (3)** -- Defines what correct output looks like.

### 5. Uniqueness (0-10)

Jaccard similarity computed on feature sets (category, tags, code patterns, name tokens) against all group members. Only one in its group = full 10. > 80% similarity to a higher-ranked peer = 0 (redundant).

### 6. Self-Containment (0-15)

Code is scanned for `tools.*` calls. Each call is classified as infrastructure (`http_request`, `web_search`) or a skill dependency.

| Situation | Score |
|-----------|-------|
| No `tools.*` calls -- fully self-contained | 15 |
| Only infrastructure tools (HTTP, search) | 10 |
| 1-2 skill dependencies | 5 |
| 3+ skill dependencies (deep chain) | 0 |

### 7. Portability (0-15)

Can someone drop this skill in and it just works?

| Check | Points |
|-------|--------|
| URLs/endpoints inline in the code | 3 |
| Clear input contract (params validated) | 3 |
| Clear output contract (structured return) | 3 |
| Complete metadata (description, category, tags) | 3 |
| No external state (no env vars, files, DB) | 3 |

### Live Verification

After scoring, published skills are tested against real APIs:

- Real HTTP requests via `java.net.http.HttpClient`
- `web_search` queries DuckDuckGo
- `http_request` fetches actual URLs
- `web_content_extract` fetches and strips HTML
- Unknown tools attempt HTTP if args contain a URL, otherwise report the tool as unavailable
- 30-second timeout per skill
- Output validated for real data (numbers, dollar amounts, structured fields)

Skills that return real, meaningful data earn `[VERIFIED]`. Everything else is archived.

## Running the Curator

```bash
# Build
cd curator && mvn package

# Preview only (no file changes)
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --dry-run

# Full evaluation + live test (recommended)
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --live-test --test-ticker AAPL

# With dependency inlining for chain-dependent skills
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --live-test --inline-deps

# Merge overlapping skills into super-skills
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --merge

# Commit only verified skills to git
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --live-test --commit
```

### CLI Options

| Flag | Default | Description |
|------|---------|-------------|
| `--skills-dir`, `-d` | `.` | Directory containing skill packages |
| `--top-k`, `-k` | `3` | Number of skills to keep per group |
| `--output-dir`, `-o` | Parent of skills-dir | Where to write CATALOG.md |
| `--live-test` | off | Test skills against real APIs. Unverified skills are archived |
| `--test-ticker` | `AAPL` | Ticker symbol for live testing financial skills |
| `--inline-deps` | off | Inline dependencies for skills that fail self-containment |
| `--merge` | off | Merge overlapping skills into super-skills |
| `--merge-min-group` | `3` | Minimum group size to trigger merging |
| `--commit` | off | Git commit only published/verified skills |
| `--dry-run` | off | Assess and report only, no file changes |

### What the Curator Prints

```
[+] skill_name    80/100 (B)    ← passes quality gate
[x] skill_name    68/100 (D)    ← rejected (below 75)

[VERIFIED] skill_name           ← real API data confirmed
[FAILED]   skill_name           ← live test failed (archived)
```

## Tech Stack

- Java 21 (preview features enabled)
- Apache Groovy 4.0 (skill execution sandbox)
- `java.net.http.HttpClient` (live API testing)
- Picocli (CLI framework)
- Jackson (JSON parsing)
- Maven (build + shade plugin for fat jar)
