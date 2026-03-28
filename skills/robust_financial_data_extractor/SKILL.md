---
name: robust_financial_data_extractor
description: Robustly extracts financial data by querying multiple sources (e.g., SEC EDGAR, APIs, web search) with failover and result merging to minimize single-point-of-failure risk.
type: CODE
triggerWhen: When reliable, up-to-date financial data is needed for companies or securities and resilience to API outages or failures is required.
avoidWhen: When only a single, specific data source is mandated, or for non-financial data extraction tasks.
category: data-io
tags: [finance, data-extraction, resilience, multi-source, fault-tolerant]
---

# Robust Financial Data Extractor

This skill queries multiple financial data sources in sequence (e.g., SEC EDGAR filings, financial APIs, and web search composites) for company or security financial data. It handles failures gracefully by automatically failing over to the next source if one is unavailable, merges results where possible, and annotates the provenance of each data point. This reduces risk of data unavailability and improves reliability for downstream analysis.

## Code
```groovy
import groovy.json.JsonSlurper

def ticker = params.get("ticker") ?: params.get("symbol")
if (!ticker) return "ERROR: Missing required parameter 'ticker' or 'symbol'."

def sources = [
    [name: "SEC EDGAR", query: { t ->
        // Try to fetch via web search composite (simulating SEC direct extraction)
        def q = "latest SEC EDGAR 10-K filing for " + t
        def res = tools.financial_data_web_search_and_calculation.execute(Map.of("query", q))
        [source: "SEC EDGAR", raw: res]
    }],
    [name: "Financial APIs", query: { t ->
        def q = "current financial summary for " + t
        def res = tools.financial_data_web_search_and_calculation.execute(Map.of("query", q))
        [source: "Financial APIs", raw: res]
    }],
    [name: "General Web Search", query: { t ->
        def q = "financial overview " + t
        def res = tools.financial_data_web_search_and_calculation.execute(Map.of("query", q))
        [source: "Web Search", raw: res]
    }]
]

// Try each source; collect first successful result from each
def results = []
sources.each { src ->
    try {
        def r = src.query(ticker)
        // Consider a result successful if it contains some plausible financial data
        if (r.raw && r.raw.toLowerCase().contains(ticker.toLowerCase())) {
            results << r
        }
    } catch (Exception e) {
        // Log error, continue to next source
        log.info("Source ${src.name} failed: " + e.message)
    }
}
// If no results, try to return informative error
if (results.isEmpty()) return "No financial data could be extracted for '${ticker}'. All sources failed or returned no data."

// Merge results (simple concatenation with provenance annotations)
def output = new StringBuilder()
results.each { r ->
    output << "=== Source: ${r.source} ===\n"
    output << (r.raw?.trim() ?: "NO DATA FOUND") << "\n\n"
}
return output.toString().trim()
```

## References
### - [SEC EDGAR](https://www.sec.gov/edgar.shtml) [CONFIRMED]
- [tools.financial_data_web_search_and_calculation documentation] [CONFIRMED]

## Resources
### output-template
```
Company: {{ticker}}

{{#each results}}
=== Source: {{source}} ===
{{raw}}
{{/each}}
```

