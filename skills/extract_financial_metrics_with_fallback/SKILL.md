---
name: extract_financial_metrics_with_fallback
description: Extracts and confirms key financial metrics (e.g., revenue, net income, EPS) for a specified company and period by composing multiple financial data tools (Yahoo Finance, SEC EDGAR). If primary extraction fails, automatically retries with robust backup aggregators, ensuring reliable metric retrieval with fallback handling.
type: CODE
triggerWhen: When financial metrics (such as revenue, net income, EPS, cash flow) must be extracted from primary financial data sources (Yahoo Finance, SEC EDGAR) and confirmation or backup fallback is required.
avoidWhen: When only qualitative financial analysis is needed, or when metrics are already confirmed and available without extraction.
category: data-io
tags: [finance, extraction, fallback, data-quality, confirmation]
---

# Extract Financial Metrics With Fallback

Extracts key financial metrics for a given company (by ticker) and period (e.g., year, quarter) from primary financial sources. If extraction or confirmation fails with the main parser, attempts fallback aggregation and backup extraction to maximize reliability. Ensures results are labeled with data provenance and fallback status.

## Code
```groovy
def ticker = params.get("ticker")
def period = params.get("period") ?: "annual" // default to annual
def metric = params.get("metric") // e.g., "revenue", "net income", "eps"
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
```

## References
### - [Yahoo Finance](https://finance.yahoo.com)
- [SEC EDGAR](https://www.sec.gov/edgar.shtml)
- No proprietary data sources used.

## Resources
### output-template
```
Financial Metric Extracted:
Ticker: {{ticker}}
Period: {{period}}
Metric: {{metric}}
Value: {{value}}
Fallback Used: {{fallbackUsed}}
Provenance: {{provenance}}
```

