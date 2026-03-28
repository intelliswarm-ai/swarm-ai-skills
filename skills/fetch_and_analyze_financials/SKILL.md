---
name: fetch_and_analyze_financials
description: Aggregates company financials from public APIs (e.g., Yahoo Finance, SEC EDGAR) and computes basic valuation ratios (P/E, EV/EBITDA, etc.) for a given company ticker or name.
type: CODE
triggerWhen: When financial data and valuation ratios are needed for a specific company, using public sources.
avoidWhen: When only qualitative analysis is required or for private companies without public filings.
category: analysis
tags: [finance, valuation, public-api, aggregation, ratio-analysis]
---

# Fetch and Analyze Financials

Aggregates key financial data from public APIs (Yahoo Finance, SEC EDGAR) using existing tools, extracts relevant financial metrics, and computes basic valuation ratios including Price/Earnings (P/E), Price/Sales (P/S), and EV/EBITDA for a given company ticker or name. Returns a structured summary with all fetched values and calculations.

## Code
```groovy
def ticker = params.get("ticker") ?: params.get("company")
if (!ticker) return "ERROR: Please provide a company ticker or name."

// Fetch raw financial data using robust extractors
def rawData = tools.extract_company_financials_robust.execute([query: ticker])

// Attempt to extract relevant fields using regex (since output is String)
def getField = { String label, String text ->
    def regex = ~/(?i)${label}[:=]?\s*([$]?[0-9,.]+)/  // e.g., "Net Income: $2,500,000"
    def m = regex.matcher(text)
    return m.find() ? m.group(1).replaceAll("[\$,]", "") : null
}

// Extract needed metrics
def marketCap = getField("Market Cap", rawData)
def netIncome = getField("Net Income", rawData)
def revenue = getField("Revenue", rawData)
def sharesOutstanding = getField("Shares Outstanding", rawData)
def ev = getField("Enterprise Value", rawData)
def ebitda = getField("EBITDA", rawData)
def price = getField("Share Price", rawData) ?: getField("Price", rawData)
def pe = null
def ps = null
def evEbitda = null

// Calculate ratios if possible
if (price && netIncome && sharesOutstanding && netIncome.toBigDecimal() > 0G && sharesOutstanding.toBigDecimal() > 0G) {
    def eps = netIncome.toBigDecimal() / sharesOutstanding.toBigDecimal()
    pe = eps > 0G ? (price.toBigDecimal() / eps).setScale(2, BigDecimal.ROUND_HALF_UP).toString() : null
}
if (price && revenue && sharesOutstanding && revenue.toBigDecimal() > 0G && sharesOutstanding.toBigDecimal() > 0G) {
    def rps = revenue.toBigDecimal() / sharesOutstanding.toBigDecimal()
    ps = rps > 0G ? (price.toBigDecimal() / rps).setScale(2, BigDecimal.ROUND_HALF_UP).toString() : null
}
if (ev && ebitda && ebitda.toBigDecimal() > 0G) {
    evEbitda = (ev.toBigDecimal() / ebitda.toBigDecimal()).setScale(2, BigDecimal.ROUND_HALF_UP).toString()
}

// Format output
def output = []
output << "Financial Summary for: ${ticker}"
output << "Market Cap: ${marketCap ?: 'DATA NOT AVAILABLE'}"
output << "Net Income: ${netIncome ?: 'DATA NOT AVAILABLE'}"
output << "Revenue: ${revenue ?: 'DATA NOT AVAILABLE'}"
output << "Shares Outstanding: ${sharesOutstanding ?: 'DATA NOT AVAILABLE'}"
output << "Share Price: ${price ?: 'DATA NOT AVAILABLE'}"
output << "Enterprise Value: ${ev ?: 'DATA NOT AVAILABLE'}"
output << "EBITDA: ${ebitda ?: 'DATA NOT AVAILABLE'}"
output << ""
output << "Valuation Ratios:"
output << "P/E Ratio: ${pe ?: 'DATA NOT AVAILABLE'}"
output << "P/S Ratio: ${ps ?: 'DATA NOT AVAILABLE'}"
output << "EV/EBITDA: ${evEbitda ?: 'DATA NOT AVAILABLE'}"

return output.join("\n")
```

## References
### - Yahoo Finance public API documentation [CONFIRMED]
- SEC EDGAR filings [CONFIRMED]

## Resources
### output-template
```
Financial Summary for: {{ticker}}
Market Cap: {{marketCap}}
Net Income: {{netIncome}}
Revenue: {{revenue}}
Shares Outstanding: {{sharesOutstanding}}
Share Price: {{price}}
Enterprise Value: {{ev}}
EBITDA: {{ebitda}}

Valuation Ratios:
P/E Ratio: {{pe}}
P/S Ratio: {{ps}}
EV/EBITDA: {{evEbitda}}
```

