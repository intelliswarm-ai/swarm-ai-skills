---
name: fallback_financials_extractor
description: Extracts and summarizes available financial metrics using internal fallback calculators when external data sources are unavailable, ensuring [CONFIRMED] data only.
type: CODE
triggerWhen: When financial data extraction is needed but external sources (APIs, web) are inaccessible or fail.
avoidWhen: When working external APIs or browsing for financial data are available and reliable.
category: computation
tags: [financials, fallback, extraction, internal-tools, data-parsing]
---

# Fallback Financials Extractor

This skill composes available internal fallback financial tools to extract and summarize financial metrics (such as P/E ratio, revenue growth, and summary stats) when direct access to external sources is unavailable. It strictly avoids fabricated data and only returns [CONFIRMED] values provided by the tools. If no [CONFIRMED] data is available, it explicitly reports the absence of information.

## Code
```groovy
def symbol = params.get("symbol")
if (!symbol) return "ERROR: No symbol provided."

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
```

## Resources
### output-template
```
[CONFIRMED] Financials for {{symbol}}:
{{results or DATA NOT AVAILABLE message}}
```

