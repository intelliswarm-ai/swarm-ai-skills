---
name: robust_backup_data_extractor
description: Provides resilient data extraction by attempting primary tool fetches and, on failure, seamlessly falling back to alternate APIs or scraping mechanisms to ensure analysis continuity when primary data sources are down.
type: CODE
triggerWhen: When reliable data extraction is critical, and primary APIs or fetch methods may be unavailable or unreliable.
avoidWhen: When only a single, specific data source is required, or fallback mechanisms are unnecessary.
category: data-io
tags: [redundancy, backup, extraction, fault-tolerance, resilience]
---

# Robust Backup Data Extractor

Ensures robust data acquisition by first attempting a primary data fetch using the designated tool or API. If the primary fetch fails (e.g., returns an error, empty content, or times out), the skill automatically retries using a backup source or alternate scraping method. This mitigates single-point-of-failure risks and maintains workflow resilience.

## Code
```groovy
import groovy.json.JsonSlurper

def target = params.get("target")
if (!target) return "ERROR: 'target' parameter required."

// Primary fetch: Try the primary API/tool (simulate with fetch_and_parse_financials)
def primaryResult = null
def primaryError = null
try {
    primaryResult = tools.fetch_and_parse_financials.execute(Map.of("param", target))
    // Heuristic: treat responses with less than 20 chars or explicit error as failed
    if (!primaryResult || primaryResult.trim().length() < 20 || primaryResult.toLowerCase().contains("error")) {
        primaryError = "Primary source returned empty or error."
    }
} catch (Exception e) {
    primaryError = "Primary fetch exception: " + e.getMessage()
}

// Fallback: Use backup API (simulate with usage_reporter), or scrape alternate source
def backupResult = null
def backupError = null
if (primaryError) {
    try {
        backupResult = tools.usage_reporter.execute(Map.of("param", target))
        if (!backupResult || backupResult.trim().length() < 20 || backupResult.toLowerCase().contains("error")) {
            backupError = "Backup source returned empty or error."
        }
    } catch (Exception ex) {
        backupError = "Backup fetch exception: " + ex.getMessage()
    }
}

// Output logic
def response = ""
if (!primaryError) {
    response += "[CONFIRMED] Data extracted from primary source.\n" + primaryResult
} else if (!backupError) {
    response += "[ESTIMATE] Primary source unavailable; data extracted from backup source.\n" + backupResult
} else {
    response += "ERROR: Both primary and backup data sources failed.\nPrimary error: " + primaryError + "\nBackup error: " + (backupError ?: "NO INFORMATION FOUND")
}
return response
```

## Resources
### output-template
```
[STATUS] Data extracted from [primary|backup] source.
[data content]
```

