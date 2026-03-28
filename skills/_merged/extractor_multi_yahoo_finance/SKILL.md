---
name: extractor_multi_yahoo_finance
description: Merged super-skill combining capabilities from 3 source skills. Base: Aggregates and cross-verifies financial data for a given symbol by querying multiple Yahoo Finance tools, parses their outputs, and returns a unified, confidence-ranked summary. Resolves data access issues by composing existing extraction, parsing, and fallback tools.. Enhanced with fallback strategies from: robust backup data extractor, robust financial data extractor.
type: CODE
triggerWhen: When robust, multi-source financial data is needed with automatic fallback.
avoidWhen: When a single specific data source is required.
category: data-io
tags: [finance, data-aggregation, yahoo, extraction, verification, redundancy, backup, fault-tolerance, resilience, data-extraction, multi-source, fault-tolerant, merged, curated]
---

# Extractor multi yahoo finance

Merged skill combining the best of 3 source skills:

- **multi_tool_yahoo_finance_data_aggregator** (score: 83/100, B)
- **robust_backup_data_extractor** (score: 80/100, B)
- **robust_financial_data_extractor** (score: 80/100, B)

## Code
```groovy
// Merged skill: combines 3 source skills
// Base: multi_tool_yahoo_finance_data_aggregator (score: 83)
// + robust_backup_data_extractor (score: 80)
// + robust_financial_data_extractor (score: 80)

def symbol = params.get("symbol")
if (!symbol) return "ERROR: 'symbol' parameter required."

def results = [:]
def errors = []

// Primary: multi_tool_yahoo_finance_data_aggregator
try {
    def primaryResult = { ->
        def results = [:]
        def errors = []
        
        // Run all available Yahoo Finance data tools
        try {
            results["extractor"] = tools.yahoo_finance_data_extractor.execute(Map.of("symbol", symbol))
        } catch (Exception e) {
            errors << "yahoo_finance_data_extractor failed: " + e.getMessage()
        }
        try {
            results["structured"] = tools.yahoo_finance_structured_financial_parser.execute(Map.of("symbol", symbol))
        } catch (Exception e) {
            errors << "yahoo_finance_structured_financial_parser failed: " + e.getMessage()
        }
        try {
            results["fallback"] = tools.yahoo_finance_fallback_browser_parser.execute(Map.of("symbol", symbol))
        } catch (Exception e) {
            errors << "yahoo_finance_fallback_browser_parser failed: " + e.getMessage()
        }
        
        // Parse JSON if possible
        def slurper = new groovy.json.JsonSlurper()
        def parsed = [:]
        results.each { k, v ->
            try {
                parsed[k] = slurper.parseText(v)
            } catch (Exception e) {
                parsed[k] = v  // Not JSON, keep as raw string
            }
        }
        
        // Field-wise reconciliation
        def fields = ["marketCap", "price", "peRatio", "dividendYield", "currency"]
        def summary = [:]
        fields.each { field ->
            def values = []
            parsed.each { src, data ->
                if (data instanceof Map && data[field] != null) {
                    values << [src: src, value: data[field]]
                }
            }
            if (values.size() == 0) {
                summary[field] = [status: "MISSING", detail: "Not found in any source"]
            } else if (values.collect { it.value }.unique().size() == 1) {
                summary[field] = [status: "CONSISTENT", value: values[0].value, sources: values.collect{it.src}]
            } else {
                summary[field] = [status: "CONFLICT", values: values]
            }
        }
        
        // Build output
        def output = new StringBuilder()
        output << "Yahoo Finance Data Aggregation for: " + symbol + "\n"
        output << (errors ? "Errors encountered: " + errors.join("; ") + "\n" : "")
        fields.each { field ->
            def s = summary[field]
            if (s.status == "CONSISTENT") {
                output << "${field}: ${s.value} [${s.status}, from ${s.sources.join(", ")}]\n"
            } else if (s.status == "CONFLICT") {
                output << "${field}: CONFLICT among sources:\n"
                s.values.each { v -> output << "  - ${v.src}: ${v.value}\n" }
            } else {
                output << "${field}: NOT FOUND in any source\n"
            }
        }
        return output.toString()
    }.call()
    if (primaryResult && primaryResult.toString().length() > 20) {
        results['primary'] = primaryResult
    }
} catch (Exception e) {
    errors << "primary (multi_tool_yahoo_finance_data_aggregator) failed: ${e.message}"
}

// Fallback 1: robust_backup_data_extractor
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
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
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_1'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 1 (robust_backup_data_extractor) failed: ${e.message}"
    }
}

// Fallback 2: robust_financial_data_extractor
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
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
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_2'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 2 (robust_financial_data_extractor) failed: ${e.message}"
    }
}

// Compose final output
if (results.isEmpty()) {
    return "No results from any source. Errors: " + errors.join("; ")
}
def output = results.values().first().toString()
if (!errors.isEmpty()) {
    output += "\n\n[Warnings: " + errors.join("; ") + "]"
}
return output

```
