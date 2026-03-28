---
name: financial_metrics_calculator_fallback
description: Provides fallback calculation and summary of core financial metrics (e.g., P/E, revenue growth) by composing available partial tools, parsing their outputs, and aggregating results even if the main calculator tool is non-functional.
type: CODE
triggerWhen: When financial metrics need to be calculated or summarized and the primary calculation tool is unavailable or returns errors.
avoidWhen: When the main financial metrics calculator tool is fully operational and producing correct results.
category: computation
tags: [finance, metrics, fallback, computation, aggregation]
---

# Financial Metrics Calculator Fallback

Composes available partial financial tools to calculate and summarize key financial metrics (such as Price/Earnings and revenue growth) when the primary tool is unavailable, non-functional, or returns errors. This skill parses and aggregates outputs from `pe_and_revenue_growth_calculator` and `pe_and_revenue_growth_summary`, providing a structured fallback output.

## Code
```groovy
def symbol = params.get("symbol")
if (!symbol) return "ERROR: 'symbol' parameter is required."

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
```

## Resources
### output-template
```
Financial Metrics for symbol: {{symbol}}
P/E: {{pe}}
Revenue Growth: {{revenue_growth}}
[WARNINGS]
{{warnings}}
```
```
```

