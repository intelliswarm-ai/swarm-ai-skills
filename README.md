# swarm-ai-skills

A curated repository of AI agent skills for the [IntelliSwarm](https://github.com/intelliswarm-ai/swarm-ai) framework.

Only skills that pass a strict automated quality gate, live verification against real APIs, **and** an LLM-as-judge evaluation are published here. Everything else is archived.

## Repository Structure

```
swarm-ai-skills/
├── CATALOG.md              # Auto-generated ranked catalog of verified skills
├── skills/                 # Only VERIFIED skill packages live here
│   └── _archived/          # Skills that failed curation or live testing
└── curator/                # The SkillCurator CLI tool (Java 21 + Groovy)
    ├── curator.yaml        # LLM judge configuration (provider, model, params)
    ├── .env.example        # Template for API keys
    └── src/                # Source code
```

Each skill package contains:

| File | Purpose |
|------|---------|
| `SKILL.md` | Skill definition: description, Groovy code, examples, output template |
| `_meta.json` | Metadata: usage stats, quality scores, version history |
| `_assessment.json` | Full assessment: structural scores, live test results, API call traces, LLM evaluation |
| `_llm_evaluation.md` | Detailed LLM judge report: analysis of definition, API calls, responses, usefulness |

## How Skills Get Published

A skill must pass **three stages** before it appears in this repository.

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
3. **Every API call is traced** -- tool name, HTTP method, URL, parameters, response length, response snippet, duration, and success/failure are recorded
4. The output is validated:
   - Must be non-null and longer than 50 characters
   - Must not start with "ERROR" or "No data"
   - Must contain real data signals (numbers, dollar amounts, percentages, structured fields)
   - Is checked against the expected output template from SKILL.md
5. Skills that return real, verifiable data earn the **VERIFIED** badge
6. Skills that fail live testing are moved to `_archived/`

**Only verified skills remain in `skills/`.** The `_assessment.json` for each verified skill contains the full live test record including API call traces:

```json
{
  "liveTest": {
    "verified": true,
    "testedAt": "2026-03-28T18:28:33",
    "executionTimeMs": 224,
    "outputLength": 14348,
    "containsRealData": true,
    "outputMatchesTemplate": true,
    "apiCallTraces": [
      {
        "toolName": "web_search",
        "method": "GET",
        "url": "https://html.duckduckgo.com/html/?q=AAPL+stock+summary",
        "responseLength": 12480,
        "success": true,
        "durationMs": 312
      }
    ]
  }
}
```

### Stage 3: LLM-as-Judge Evaluation

Skills that pass live verification are submitted to an LLM judge for deep qualitative analysis. The judge receives the full skill context -- definition, code, API call traces with response data, and the actual output -- and produces a structured evaluation.

#### Five Evaluation Dimensions

| Dimension | Weight | What the LLM Evaluates |
|-----------|--------|----------------------|
| Tool Definition Accuracy | 15% | Does SKILL.md (description, triggerWhen, avoidWhen, tags, output template) accurately describe what the code actually does? |
| API Call Quality | 25% | Are the right APIs/endpoints called? Are query parameters correctly constructed? Is the call pattern efficient? Are there unnecessary or redundant calls? |
| Response Handling | 20% | Does the code correctly parse API responses? Handle errors, missing fields, unexpected formats? Is there fallback logic? |
| Output Quality | 20% | Is the final output useful, well-structured, and complete? Does it match the output template? Would a user be satisfied? |
| Usefulness | 20% | Would a real user benefit from this tool? Is it better than manual alternatives? Can it be relied on? |

Each dimension is scored 0-10 by the LLM, then weighted to produce an overall score (0-100).

#### What the Judge Sees

The evaluation prompt includes everything needed for a thorough assessment:

- **Full SKILL.md** -- the complete tool definition, code, and documentation
- **Metadata** -- category, tags, usage statistics, success rates
- **Structural assessment scores** -- all 7 automated dimension scores with notes
- **API call traces** -- every external HTTP call with URL, parameters, response snippets, timing, and success/failure
- **Actual output** -- the real output produced during live testing

This allows the judge to evaluate not just the code in isolation, but how it actually behaves when calling real APIs and what it produces.

#### Evaluation Output

Each evaluated skill gets two outputs:

**`_assessment.json`** -- the `llmEvaluation` section is added alongside existing scores:

```json
{
  "llmEvaluation": {
    "scores": {
      "toolDefinitionAccuracy": 7,
      "apiCallQuality": 6,
      "responseHandling": 5,
      "outputQuality": 7,
      "usefulnessScore": 6,
      "overall": 62
    },
    "verdict": "Useful but fragile — regex parsing breaks on format changes",
    "strengths": ["Multi-source data aggregation", "Good error messages"],
    "weaknesses": ["Regex-based HTML parsing is brittle"],
    "recommendations": ["Use structured API endpoints instead of scraping"]
  }
}
```

**`_llm_evaluation.md`** -- a detailed human-readable report with multi-paragraph analyses for each dimension, plus actionable recommendations

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
- **Every API call is traced** with full request/response metadata
- 30-second timeout per skill
- Output validated for real data (numbers, dollar amounts, structured fields)

Skills that return real, meaningful data earn `[VERIFIED]`. Everything else is archived.

## Configuration

### LLM Judge Setup

The LLM-as-judge requires an API key and a configuration file.

**1. Create your `.env` file:**

```bash
cp curator/.env.example curator/.env
# Edit curator/.env and add your API key
```

The `.env` file holds your API key (never committed to git):

```env
# Anthropic (default)
ANTHROPIC_API_KEY=sk-ant-api03-...

# Or OpenAI
# OPENAI_API_KEY=sk-...
```

API keys are resolved in order: `.env` file, then system environment variables.

**2. Configure the model in `curator.yaml`:**

```yaml
llm:
  provider: anthropic          # "anthropic" or "openai"
  model: claude-sonnet-4-20250514   # Model identifier
  maxTokens: 4096             # Max tokens for judge response
  temperature: 0.2            # Lower = more deterministic

curator:
  publicationBar: 75           # Minimum score for publication
  topK: 3                     # Skills to keep per group
```

Supported providers and models:

| Provider | Models |
|----------|--------|
| `anthropic` | `claude-sonnet-4-20250514`, `claude-opus-4-20250514`, `claude-haiku-4-5-20251001` |
| `openai` | `gpt-4o`, `gpt-4o-mini`, `o3-mini` |

You can also set `baseUrl` in `curator.yaml` to point to a custom API endpoint (e.g., a proxy or local model server).

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

# Full pipeline: quality gate + live test + LLM judge
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --live-test --llm-judge

# LLM judge with custom config path
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --live-test --llm-judge --config /path/to/curator.yaml

# With dependency inlining for chain-dependent skills
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --live-test --inline-deps

# Merge overlapping skills into super-skills
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --merge

# Commit only verified skills to git
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --top-k 3 --live-test --commit

# Everything at once: live test + LLM judge + commit
java --enable-preview -jar curator/target/curator.jar \
  --skills-dir ./skills --live-test --llm-judge --commit
```

### CLI Options

| Flag | Default | Description |
|------|---------|-------------|
| `--skills-dir`, `-d` | `.` | Directory containing skill packages |
| `--top-k`, `-k` | `3` | Number of skills to keep per group |
| `--output-dir`, `-o` | Parent of skills-dir | Where to write CATALOG.md |
| `--live-test` | off | Test skills against real APIs. Unverified skills are archived |
| `--test-ticker` | `AAPL` | Ticker symbol for live testing financial skills |
| `--llm-judge` | off | Run LLM-as-judge evaluation (requires `curator.yaml` + `.env`) |
| `--config`, `-c` | `curator.yaml` | Path to curator.yaml config file |
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

With `--llm-judge` enabled, the output also includes:

```
========================================
  LLM-AS-JUDGE EVALUATION
========================================
Provider: anthropic | Model: claude-sonnet-4-20250514
Temperature: 0.2 | Max tokens: 4096

  LLM JUDGE: fetch_financial_data                ... DONE (8234ms) — 62/100 "Useful but fragile"
    Definition: 7/10  API Calls: 6/10  Response: 5/10  Output: 7/10  Useful: 6/10
    Strengths: Multi-source data; Good error messages
    Weaknesses: Brittle regex parsing; No API rate limiting
```

### Pipeline Stages

The curator runs stages in sequence. Each stage is opt-in:

```
Scan → Assess (always) → Inline Deps (--inline-deps) → Group & Rank (always)
    → Live Test (--live-test) → LLM Judge (--llm-judge)
    → Merge (--merge) → Git Commit (--commit)
```

The `--llm-judge` flag works best combined with `--live-test`, since the judge receives the API call traces and actual output from live testing. Without live test data, the judge evaluates based on code analysis and SKILL.md alone.

## Tech Stack

- Java 21 (preview features enabled)
- Apache Groovy 4.0 (skill execution sandbox)
- `java.net.http.HttpClient` (live API testing + LLM API calls)
- Picocli (CLI framework)
- Jackson (JSON parsing)
- SnakeYAML (configuration loading)
- Maven (build + shade plugin for fat jar)
