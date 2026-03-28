---
name: extract_financials_company_ticker
description: Merged super-skill combining capabilities from 3 source skills. Base: Attempts multiple web-based toolchains to extract basic company financials (revenue, profit, valuation, etc) from well-known sources, parsing and consolidating results for reliability in cases where standard web search mechanisms fail or return incomplete data.. Enhanced with fallback strategies from: financials by ticker extractor, extract financial metrics with fallback.
type: CODE
triggerWhen: When robust, multi-source financial data is needed with automatic fallback.
avoidWhen: When a single specific data source is required.
category: data-io
tags: [finance, web-scraping, data-fusion, company-financials, extraction, ticker, yahoo-finance, sec, automation, fallback, data-quality, confirmation, merged, curated]
---

# Extract financials company ticker

Merged skill combining the best of 3 source skills:

- **extract_company_financials_robust** (score: 92/100, A)
- **financials_by_ticker_extractor** (score: 86/100, B)
- **extract_financial_metrics_with_fallback** (score: 81/100, B)

## Code
```groovy
// Merged skill: combines 3 source skills
// Base: extract_company_financials_robust (score: 92)
// + financials_by_ticker_extractor (score: 86)
// + extract_financial_metrics_with_fallback (score: 81)

def company = params.get("company")?.toString()?.trim()

def results = [:]
def errors = []

// Primary: extract_company_financials_robust
try {
    def primaryResult = { ->
        if (!company) return "[ERROR] No company specified."
        
        def results = []
        def errors = []
        
        // 1. Try robust_financial_data_extractor first (likely best structured output)
        try {
            def robustRaw = tools.robust_financial_data_extractor.execute(Map.of("company", company))
            if (robustRaw && robustRaw.trim().length() > 0) {
                // Try to extract JSON if possible
                def jsonCandidate = robustRaw.find(/\{[\s\S]+?\}/)
                if (jsonCandidate) {
                    try {
                        def data = new groovy.json.JsonSlurper().parseText(jsonCandidate)
                        results << [source: "robust_financial_data_extractor", data: data]
                    } catch (Exception e) {
                        // Not JSON, just keep raw
                        results << [source: "robust_financial_data_extractor", data: robustRaw]
                    }
                } else {
                    results << [source: "robust_financial_data_extractor", data: robustRaw]
                }
            }
        } catch (Exception e) {
            errors << "robust_financial_data_extractor failed: " + e.getMessage()
        }
        
        // 2. Also try financial_data_web_search_and_calculation for redundancy
        try {
            def webRaw = tools.financial_data_web_search_and_calculation.execute(Map.of("company", company))
            if (webRaw && webRaw.trim().length() > 0) {
                // Try to extract JSON if possible
                def jsonCandidate = webRaw.find(/\{[\s\S]+?\}/)
                if (jsonCandidate) {
                    try {
                        def data = new groovy.json.JsonSlurper().parseText(jsonCandidate)
                        results << [source: "financial_data_web_search_and_calculation", data: data]
                    } catch (Exception e) {
                        results << [source: "financial_data_web_search_and_calculation", data: webRaw]
                    }
                } else {
                    results << [source: "financial_data_web_search_and_calculation", data: webRaw]
                }
            }
        } catch (Exception e) {
            errors << "financial_data_web_search_and_calculation failed: " + e.getMessage()
        }
        
        // 3. If both failed or results look empty, try auto_tool_composer as last resort
        if (results.size() == 0 || results.every { (it.data instanceof String) && it.data.trim().isEmpty() }) {
            try {
                def autoRaw = tools.auto_tool_composer.execute(Map.of("query", company + " latest revenue profit valuation"))
                if (autoRaw && autoRaw.trim().length() > 0) {
                    results << [source: "auto_tool_composer", data: autoRaw]
                }
            } catch (Exception e) {
                errors << "auto_tool_composer failed: " + e.getMessage()
            }
        }
        
        // 4. Merge and deduplicate by key info
        def merged = [:]
        results.each { entry ->
            def data = entry.data
            if (data instanceof Map) {
                data.each { k, v ->
                    if (v && !merged.containsKey(k)) merged[k] = [v, entry.source]
                }
            } else if (data instanceof String) {
                // Try to extract key financials with regex
                def patts = [
                    "revenue": ~/([Rr]evenue)[^\d\$]{0,10}([\$\d\.,]+[MBT]?)\b/,
                    "profit": ~/([Nn]et (income|profit|earnings))[^\d\$]{0,10}([\$\d\.,]+[MBT]?)\b/,
                    "valuation": ~/([Vv]aluation|[Mm]arket ?[Cc]ap)[^\d\$]{0,10}([\$\d\.,]+[MBT]?)\b/
                ]
                patts.each { key, patt ->
                    def m = data =~ patt
                    if (m && m[0] && m[0][2]) {
                        if (!merged.containsKey(key)) merged[key] = [m[0][2], entry.source]
                    }
                }
            }
        }
        
        // 5. Prepare output
        def output = new StringBuilder()
        output << "# Financials for ${company}\n"
        if (merged.size() == 0) {
            output << "No structured financial data found.\n"
            if (errors.size() > 0) output << "Errors: " + errors.join("; ") + "\n"
            output << "Raw Results:\n"
            results.each { entry ->
                output << "- [${entry.source}] ${entry.data.toString().take(500)}\n"
            }
        } else {
            merged.each { k, val ->
                output << "- ${k.capitalize()}: ${val[0]} [source: ${val[1]}]\n"
            }
            if (errors.size() > 0) output << "\nErrors: " + errors.join("; ") + "\n"
        }
        return output.toString()
    }.call()
    if (primaryResult && primaryResult.toString().length() > 20) {
        results['primary'] = primaryResult
    }
} catch (Exception e) {
    errors << "primary (extract_company_financials_robust) failed: ${e.message}"
}

// Fallback 1: financials_by_ticker_extractor
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
            if (!ticker || !(ticker ==~ /^[A-Za-z.\-]{1,10}$/)) {
                return "ERROR: Valid 'ticker' parameter required (e.g., AAPL, MSFT, BRK.B)."
            }
            
            def responses = []
            def dataSources = [
                [tool: tools.yahoo_finance_data_extractor, label: "Yahoo Finance"],
                [tool: tools.fallback_financials_extractor, label: "Fallback Extractor"]
            ]
            
            for (source in dataSources) {
                def res = source.tool.execute(Map.of("ticker", ticker))
                if (res && res.trim() && !res.toLowerCase().contains("no data") && !res.toLowerCase().contains("error")) {
                    responses << "Source: ${source.label}\n${res.trim()}"
                    break
                }
            }
            
            if (responses.isEmpty()) {
                return "No financial data found for ticker '${ticker}'. Please verify the ticker symbol or try a different company."
            }
            
            return responses.join("\n---\n")
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_1'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 1 (financials_by_ticker_extractor) failed: ${e.message}"
    }
}

// Fallback 2: extract_financial_metrics_with_fallback
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
            if (!ticker || !metric) {
                return "ERROR: 'ticker' and 'metric' parameters are required."
            }
            
            def result = [:]
            def provenance = []
            def fallbackUsed = false
            
            // 1. Try primary tool: fetch_and_parse_financials
            def primaryOutput = tools.fetch_and_parse_financials.execute([
                "ticker": ticker,
                "period": period,
                "metric": metric
            ])
            provenance << "[CONFIRMED] Source: fetch_and_parse_financials"
            
            def matchedValue = null
            def pattern = ~/(?i)${metric.replaceAll("\\s+","[_\\s]?")}\s*[:=]?\s*([-,.\d]+(?:[MBT]|\d+)?(?:\s*USD)?)/
            
            primaryOutput.split('\n').each { line ->
                def m = (line =~ pattern)
                if (m && m[0]?.size() > 1) {
                    matchedValue = m[0][1].trim()
                }
            }
            
            if (!matchedValue) {
                // 2. Try robust_financial_data_aggregator as fallback
                fallbackUsed = true
                provenance << "[ESTIMATE] Source: robust_financial_data_aggregator"
                def fallbackOutput = tools.robust_financial_data_aggregator.execute([
                    "ticker": ticker,
                    "period": period,
                    "metric": metric
                ])
                fallbackOutput.split('\n').each { line ->
                    def m = (line =~ pattern)
                    if (m && m[0]?.size() > 1) {
                        matchedValue = m[0][1].trim()
                    }
                }
            }
            
            // 3. If still not found, try robust_backup_data_extractor as last resort
            if (!matchedValue) {
                fallbackUsed = true
                provenance << "[ESTIMATE] Source: robust_backup_data_extractor"
                def backupOutput = tools.robust_backup_data_extractor.execute([
                    "ticker": ticker,
                    "period": period,
                    "metric": metric
                ])
                backupOutput.split('\n').each { line ->
                    def m = (line =~ pattern)
                    if (m && m[0]?.size() > 1) {
                        matchedValue = m[0][1].trim()
                    }
                }
            }
            
            // 4. Return result with provenance and fallback info
            if (matchedValue) {
                result.metric = metric
                result.value = matchedValue
                result.ticker = ticker
                result.period = period
                result.fallbackUsed = fallbackUsed
                result.provenance = provenance.join(" -> ")
                return "Financial Metric Extracted:\nTicker: ${ticker}\nPeriod: ${period}\nMetric: ${metric}\nValue: ${matchedValue}\nFallback Used: ${fallbackUsed}\nProvenance: ${result.provenance}"
            } else {
                return "DATA NOT AVAILABLE: Unable to extract or confirm '${metric}' for ${ticker} (${period}) from any available source."
            }
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_2'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 2 (extract_financial_metrics_with_fallback) failed: ${e.message}"
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
