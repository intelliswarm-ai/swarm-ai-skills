---
name: financials_by_ticker_extractor
description: Retrieves and parses real company financial statements and key metrics by ticker symbol using Yahoo Finance and fallback extractors, ensuring robust, automated access to reliable financial data for analysis and reporting.
type: CODE
triggerWhen: Use when automated, robust retrieval of a company's financial statements or key metrics is required by stock ticker.
avoidWhen: Do not use if the company is not publicly traded or no ticker is available; avoid for qualitative/subjective company analysis.
category: data-io
tags: [finance, extraction, ticker, yahoo-finance, sec, automation]
---

# Financials by Ticker Extractor

This skill retrieves core financial statement data (income statement, balance sheet, cash flow, and key financial metrics) for a given company ticker. It first attempts retrieval via Yahoo Finance, falling back to alternative extractors if necessary. Output includes structured, labeled metrics suitable for downstream analysis.

## Code
```groovy
def ticker = params.get("ticker")
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
```

## References
### - https://finance.yahoo.com/
- DATA NOT AVAILABLE for SEC direct API tool in this environment.

## Resources
### output-template
```
Source: [Data Source Name]
[Financial data block: clearly labeled metrics, dates, and values]
```

## Examples
### Example 1
**Input:** `{ "ticker": "AAPL" }`
**Expected Output:** Source: Yahoo Finance  
Revenue (2025): $405.2B  
Net Income (2025): $96.3B  
EPS (2025): $6.14  
...  
---

### Example 2
**Input:** `{ "ticker": "FAKE123" }`
**Expected Output:** No financial data found for ticker 'FAKE123'. Please verify the ticker symbol or try a different company.

