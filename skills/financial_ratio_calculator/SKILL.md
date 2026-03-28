---
name: financial_ratio_calculator
description: Computes key financial ratios (e.g., P/E ratio, revenue growth) by fetching and processing up-to-date financial data for a given company symbol using integrated data-fetch and calculation logic.
type: CODE
triggerWhen: When a user requests calculation of financial ratios (such as P/E, revenue growth, profit margin) for a specific company or symbol, especially if only raw data is available.
avoidWhen: When only qualitative financial analysis is required, or if the user does not specify a company/symbol or a computable ratio.
category: computation
tags: [finance, ratios, data-processing, financial-analysis, pe-ratio, revenue-growth]
---

# Financial Ratio Calculator

Fetches financial data for a specified company symbol and computes requested financial ratios, such as Price-to-Earnings (P/E) ratio, revenue growth, and profit margin. This skill composes data fetching and in-line computation to provide actionable financial metrics in a single step.

## Code
```groovy
import groovy.json.JsonSlurper

def symbol = params.get("symbol")
def ratio = params.get("ratio")?.toLowerCase()
if (!symbol || !ratio) return "ERROR: Both 'symbol' and 'ratio' parameters are required."

def raw = tools.fetch_financial_data.execute(Map.of("symbol", symbol))
if (!raw || raw.trim().length() == 0) return "ERROR: No financial data found for symbol '${symbol}'."

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
```

## Resources
### output-template
```
{{ratio}} for {{symbol}}: {{computed_value}}
```

