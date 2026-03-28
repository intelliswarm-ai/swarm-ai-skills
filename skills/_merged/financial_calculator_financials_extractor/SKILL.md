---
name: financial_calculator_financials_extractor
description: Merged super-skill combining capabilities from 3 source skills. Base: Extracts and summarizes available financial metrics using internal fallback calculators when external data sources are unavailable, ensuring [CONFIRMED] data only.. Enhanced with fallback strategies from: financial metrics calculator fallback, financial ratio calculator.
type: CODE
triggerWhen: When robust, multi-source financial data is needed with automatic fallback.
avoidWhen: When a single specific data source is required.
category: computation
tags: [financials, fallback, extraction, internal-tools, data-parsing, finance, metrics, computation, aggregation, ratios, data-processing, financial-analysis, pe-ratio, revenue-growth, merged, curated]
---

# Financial calculator financials extractor

Merged skill combining the best of 3 source skills:

- **fallback_financials_extractor** (score: 82/100, B)
- **financial_metrics_calculator_fallback** (score: 82/100, B)
- **financial_ratio_calculator** (score: 80/100, B)

## Code
```groovy
// Merged skill: combines 3 source skills
// Base: fallback_financials_extractor (score: 82)
// + financial_metrics_calculator_fallback (score: 82)
// + financial_ratio_calculator (score: 80)

def symbol = params.get("symbol")
if (!symbol) return "ERROR: No symbol provided."

def results = [:]
def errors = []

// Primary: fallback_financials_extractor
try {
    def primaryResult = { ->
        def results = []
        
        def peGrowth = tools.pe_and_revenue_growth_calculator.execute(Map.of("param", symbol))
        if (peGrowth && peGrowth.trim().length() > 0 && !peGrowth.toLowerCase().contains("no data")) {
            results << "[CONFIRMED] P/E and revenue growth:\n" + peGrowth.trim()
        }
        
        def peSummary = tools.pe_and_revenue_growth_summary.execute(Map.of("param", symbol))
        if (peSummary && peSummary.trim().length() > 0 && !peSummary.toLowerCase().contains("no data")) {
            results << "[CONFIRMED] P/E and revenue growth summary:\n" + peSummary.trim()
        }
        
        def fallbackMetrics = tools.financial_metrics_calculator_fallback.execute(Map.of("param", symbol))
        if (fallbackMetrics && fallbackMetrics.trim().length() > 0 && !fallbackMetrics.toLowerCase().contains("no data")) {
            results << "[CONFIRMED] Fallback financial metrics:\n" + fallbackMetrics.trim()
        }
        
        if (results.isEmpty()) {
            return "DATA NOT AVAILABLE: No [CONFIRMED] financials found for symbol '" + symbol + "'."
        } else {
            return results.join("\n\n")
        }
    }.call()
    if (primaryResult && primaryResult.toString().length() > 20) {
        results['primary'] = primaryResult
    }
} catch (Exception e) {
    errors << "primary (fallback_financials_extractor) failed: ${e.message}"
}

// Fallback 1: financial_metrics_calculator_fallback
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
            def errors = []
            def results = [:]
            
            // Try to get P/E and Revenue Growth Calculation
            def calcResult = null
            try {
                calcResult = tools.pe_and_revenue_growth_calculator.execute([symbol: symbol])
                // Attempt to parse key financial metrics using regex
                def peMatcher = (calcResult =~ /P\/E\s*:\s*([\d.]+)/)
                if (peMatcher.find()) results["P/E"] = peMatcher.group(1)
                def revenueMatcher = (calcResult =~ /Revenue\s*Growth\s*:\s*([-\d.]+%?)/)
                if (revenueMatcher.find()) results["Revenue Growth"] = revenueMatcher.group(1)
            } catch (Exception e) {
                errors << "pe_and_revenue_growth_calculator failed: ${e.getMessage()}"
            }
            
            // Try to get Summary (which may include additional context)
            def summaryResult = null
            try {
                summaryResult = tools.pe_and_revenue_growth_summary.execute([symbol: symbol])
                // Attempt to extract additional metrics from summary if not already present
                if (!results["P/E"]) {
                    def peMatcher2 = (summaryResult =~ /P\/E\s*ratio\s*is\s*([\d.]+)/)
                    if (peMatcher2.find()) results["P/E"] = peMatcher2.group(1)
                }
                if (!results["Revenue Growth"]) {
                    // Groovy regex flags must be set with (?i) and not with /i at the end
                    def revMatcher2 = (summaryResult =~ /(?i)revenue\s*growth\s*(?:rate)?\s*(?:is|:)?\s*([-\d.]+%?)/)
                    if (revMatcher2.find()) results["Revenue Growth"] = revMatcher2.group(1)
                }
            } catch (Exception e) {
                errors << "pe_and_revenue_growth_summary failed: ${e.getMessage()}"
            }
            
            // Aggregate output
            def output = []
            output << "Financial Metrics for symbol: ${symbol}"
            if (results["P/E"]) output << "P/E: ${results["P/E"]}"
            if (results["Revenue Growth"]) output << "Revenue Growth: ${results["Revenue Growth"]}"
            if (output.size() == 1) output << "No metrics could be extracted from available tools."
            if (!errors.isEmpty()) output << "\n[WARNINGS]\n" + errors.join("\n")
            return output.join("\n")
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_1'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 1 (financial_metrics_calculator_fallback) failed: ${e.message}"
    }
}

// Fallback 2: financial_ratio_calculator
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
            def raw = tools.fetch_financial_data.execute(Map.of("symbol", symbol))
            
            def json
            try {
                json = new JsonSlurper().parseText(raw)
            } catch (Exception ex) {
                return "ERROR: Failed to parse financial data for symbol '${symbol}'."
            }
            
            // Helper: get nested value or null
            def getVal = { path ->
                def parts = path.split(/\./)
                def v = json
                for (p in parts) { if (v instanceof Map && v.containsKey(p)) { v = v[p] } else { return null } }
                return v
            }
            
            // Calculation logic
            def output = ""
            if (ratio == "p/e" || ratio == "pe" || ratio == "price to earnings" || ratio == "price/earnings") {
                def price = getVal("price") ?: getVal("currentPrice") ?: getVal("quote.price")
                def eps = getVal("eps") ?: getVal("earningsPerShare") ?: getVal("financials.eps")
                if (price == null || eps == null || eps == 0) {
                    output = "ERROR: Could not compute P/E ratio – missing price or EPS for '${symbol}'."
                } else {
                    def pe = (price as BigDecimal) / (eps as BigDecimal)
                    output = "P/E ratio for ${symbol}: " + pe.setScale(2, BigDecimal.ROUND_HALF_UP).toString()
                }
            }
            else if (ratio == "revenue growth" || ratio == "rev growth" || ratio == "revenue_growth") {
                def revHist = getVal("financials.revenue_history") ?: getVal("revenueHistory")
                if (revHist instanceof List && revHist.size() >= 2) {
                    def rev0 = revHist[-2] as BigDecimal
                    def rev1 = revHist[-1] as BigDecimal
                    if (rev0 == 0) {
                        output = "ERROR: Previous period revenue is zero, cannot compute growth."
                    } else {
                        def growth = ((rev1 - rev0) / rev0) * 100
                        output = "Revenue growth for ${symbol} (most recent period): " + growth.setScale(2, BigDecimal.ROUND_HALF_UP).toString() + "%"
                    }
                } else if (getVal("revenue") && getVal("revenue_prev")) {
                    def rev0 = getVal("revenue_prev") as BigDecimal
                    def rev1 = getVal("revenue") as BigDecimal
                    if (rev0 == 0) {
                        output = "ERROR: Previous period revenue is zero, cannot compute growth."
                    } else {
                        def growth = ((rev1 - rev0) / rev0) * 100
                        output = "Revenue growth for ${symbol} (most recent period): " + growth.setScale(2, BigDecimal.ROUND_HALF_UP).toString() + "%"
                    }
                } else {
                    output = "ERROR: Revenue history not found for '${symbol}'."
                }
            }
            else if (ratio == "profit margin" || ratio == "net margin" || ratio == "profit_margin") {
                def netIncome = getVal("netIncome") ?: getVal("financials.net_income")
                def revenue = getVal("revenue") ?: getVal("financials.revenue")
                if (netIncome == null || revenue == null || revenue == 0) {
                    output = "ERROR: Cannot compute profit margin – missing net income or revenue."
                } else {
                    def margin = ((netIncome as BigDecimal) / (revenue as BigDecimal)) * 100
                    output = "Profit margin for ${symbol}: " + margin.setScale(2, BigDecimal.ROUND_HALF_UP).toString() + "%"
                }
            }
            else {
                output = "ERROR: Unsupported ratio type '${ratio}'. Supported: P/E, revenue growth, profit margin."
            }
            
            return output
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_2'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 2 (financial_ratio_calculator) failed: ${e.message}"
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
