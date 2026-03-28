---
name: financial_data_web_search_and_calculation
description: Retrieves recent financial data using general web search as a fallback, parses key metrics (price, market cap, EPS, etc.), and computes common financial ratios when standard financial APIs/tools are unavailable.
type: CODE
triggerWhen: When financial data (such as stock price, market cap, P/E ratio, EPS, etc.) is required but direct financial data sources are unavailable or nonfunctional.
avoidWhen: When validated, up-to-date data is available directly from financial APIs or trusted structured data sources.
category: data-io
tags: [finance, web_search, data_extraction, fallback, computation]
---

# Financial Data Web Search and Calculation

## Code
```groovy
import groovy.json.JsonSlurper

def symbol = params.get("symbol")
def company = params.get("company")
def metrics = params.get("metrics") ?: ["price", "market cap", "eps", "p/e"]

if (!symbol && !company) return "ERROR: Provide at least 'symbol' or 'company' parameter."

def queryPieces = []
if (symbol) queryPieces << symbol
if (company) queryPieces << company
queryPieces << "stock"
def searchQuery = queryPieces.join(" ") + " " + metrics.join(" ")

def searchResult = tools.auto_tool_composer.execute(Map.of(
    "query", searchQuery,
    "max_results", "5"
))

if (!searchResult || searchResult.toLowerCase().contains("no results")) {
    return "Search returned no relevant results for financial data on: ${symbol ?: company}."
}

// Attempt to extract metrics from unstructured search results
def extracted = [:]

metrics.each { metric ->
    def regex
    switch (metric.toLowerCase()) {
        case "price":
            regex = /(?i)(stock price|current price|share price)[^\d\$]{0,20}([\$]?\d{1,3}(?:[\,\d]{0,3})*(?:\.\d{1,2})?)/
            break
        case "market cap":
            regex = /(?i)(market cap(?:italization)?)[^\d\$]{0,20}([\$]?\d{1,3}(?:[\,\d]{0,3})*(?:\.\d{1,2})?\s*(?:[MBT]n|billion|million|trillion)?)/ 
            break
        case "eps":
            regex = /(?i)(eps|earnings per share)[^\d\-]{0,20}([\$]?\-?\d{1,3}(?:[\,\d]{0,3})*(?:\.\d{1,2})?)/
            break
        case "p/e":
        case "pe":
        case "p/e ratio":
            regex = /(?i)(p\/e\s*ratio|pe\s*ratio)[^\d]{0,20}(\d{1,3}(?:\.\d{1,2})?)/
            break
        default:
            regex = null
    }
    if (regex) {
        def m = (searchResult =~ regex)
        if (m && m.count > 0) {
            extracted[metric] = m[0][2].replaceAll(",", "").trim()
        }
    }
}

// Attempt to compute P/E if price and EPS were found but not P/E
if (!extracted["p/e"] && extracted["price"] && extracted["eps"]) {
    try {
        def p = extracted["price"].replaceAll(/[^0-9.\-]/,'').toBigDecimal()
        def e = extracted["eps"].replaceAll(/[^0-9.\-]/,'').toBigDecimal()
        if (e != 0) {
            def pe = (p / e).setScale(2, BigDecimal.ROUND_HALF_UP)
            extracted["p/e"] = pe.toString() + " [ESTIMATE]"
        }
    } catch (Exception ex) {
        // Ignore calculation errors
    }
}

def outputLines = []
outputLines << "Financial data for: ${symbol ?: company}"
if (extracted) {
    metrics.each { m ->
        if (extracted[m]) {
            outputLines << "${m}: ${extracted[m]}"
        } else {
            outputLines << "${m}: DATA NOT AVAILABLE"
        }
    }
} else {
    outputLines << "No recognizable financial metrics found in web search results."
}
outputLines << "\n[Method: Web search fallback. Data may be estimated or outdated. Use with caution.]"

return outputLines.join("\n")
```

## Resources
### output-template
```
Financial data for: {{symbol}} / {{company}}
price: {{price}}
market cap: {{market_cap}}
eps: {{eps}}
p/e: {{pe}}
[Method: Web search fallback. Data may be estimated or outdated. Use with caution.]
```

