---
name: alternative_financial_data_retriever
description: Retrieves financial data by directly querying public financial APIs or scraping fallback web sources when built-in retrieval tools are non-functional, enabling essential downstream analysis.
type: CODE
triggerWhen: When financial data is needed but fetch_financial_data or authenticated_financial_data_retriever are unavailable, misconfigured, or return errors.
avoidWhen: When built-in financial data retrieval tools are functional and return valid results.
category: data-io
tags: [financial, data, retrieval, fallback, api, web]
---

# Alternative Financial Data Retriever

This skill attempts to retrieve up-to-date financial data (e.g., stock prices, company fundamentals, ratios) from public APIs or web sources when core data retrieval tools fail or are misconfigured. It enables downstream analysis workflows by providing a robust fallback pathway for critical financial information.

## Code
```groovy
def symbol = params.get("symbol")
def datatype = params.get("datatype") ?: "price" // e.g., "price", "summary", "ratios"
if (!symbol) return "[ERROR] No symbol provided."

// Helper: Check if built-in tool is available and returns valid data
def tryBuiltinTools = {
    def results = []
    try {
        def out1 = tools.fetch_financial_data.execute(Map.of("symbol", symbol, "datatype", datatype))
        if (out1 && !out1.toLowerCase().contains("error") && out1.length() > 10) results << out1
    } catch (ignored) {}
    try {
        def out2 = tools.authenticated_financial_data_retriever.execute(Map.of("symbol", symbol, "datatype", datatype))
        if (out2 && !out2.toLowerCase().contains("error") && out2.length() > 10) results << out2
    } catch (ignored) {}
    results
}

def results = tryBuiltinTools()
if (!results.isEmpty()) return "[BUILTIN] " + results.join("\n---\n")

// Fallback: Try public APIs (e.g., Yahoo Finance, Alpha Vantage, Financial Modeling Prep)
def apiEndpoints = [
    // Yahoo Finance unofficial API (no key required for price and summary)
    [
        name: "Yahoo Finance",
        url: "https://query1.finance.yahoo.com/v7/finance/quote?symbols=${URLEncoder.encode(symbol, 'UTF-8')}",
        parse: { resp ->
            try {
                def js = new groovy.json.JsonSlurper().parseText(resp)
                def res = js?.quoteResponse?.result?.find { it.symbol?.toUpperCase() == symbol.toUpperCase() }
                if (!res) return null
                switch(datatype) {
                    case "price":
                        return "Price: " + res.regularMarketPrice + " " + res.currency + " (as of " + res.regularMarketTime + ")"
                    case "summary":
                        return "Summary:\nName: " + res.shortName + "\nPrice: " + res.regularMarketPrice + " " + res.currency + "\nChange: " + res.regularMarketChange + " (" + res.regularMarketChangePercent + "%)"
                    default:
                        return "Raw: " + groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(res))
                }
            } catch (ignored) { return null }
        }
    ],
    // Financial Modeling Prep (limited free, no API key for quote endpoint)
    [
        name: "Financial Modeling Prep",
        url: "https://financialmodelingprep.com/api/v3/quote/${URLEncoder.encode(symbol, 'UTF-8')}",
        parse: { resp ->
            try {
                def js = new groovy.json.JsonSlurper().parseText(resp)
                if (!js || js.isEmpty()) return null
                def res = js[0]
                switch(datatype) {
                    case "price":
                        return "Price: " + res.price + " " + res.currency + " (as of " + res.timestamp + ")"
                    case "summary":
                        return "Summary:\nName: " + res.name + "\nPrice: " + res.price + " " + res.currency + "\nChange: " + res.change + " (" + res.changesPercentage + "%)"
                    default:
                        return "Raw: " + groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(res))
                }
            } catch (ignored) { return null }
        }
    ]
]

def fetchFromApi = { endpoint ->
    try {
        def resp = tools.http_request_json_parser.execute(Map.of("url", endpoint.url))
        if (resp && resp.length() > 5) {
            def parsed = endpoint.parse(resp)
            if (parsed) return "[${endpoint.name}] " + parsed
        }
    } catch (ignored) {}
    null
}

def apiResults = []
for (ep in apiEndpoints) {
    def r = fetchFromApi(ep)
    if (r) apiResults << r
}
if (!apiResults.isEmpty()) return apiResults.join("\n---\n")

// Final fallback: Report insufficient tool gap
def gapReport = tools.insufficient_tool_gap_reporter.execute(Map.of(
    "symbol", symbol,
    "datatype", datatype,
    "reason", "Built-in and fallback data retrieval failed"
))
return "[ERROR] Unable to retrieve financial data for symbol '${symbol}'.\n" + gapReport
```

## References
### - Yahoo Finance Unofficial API: https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL
- Financial Modeling Prep API: https://financialmodelingprep.com/developer/docs/

## Resources
### output-template
```
[DATA_SOURCE] Data summary or error message
---
[DATA_SOURCE] Data summary or error message
```

## Examples
### Example 1
**Input:** params = [symbol: "AAPL", datatype: "price"]
**Expected Output:** [Yahoo Finance] Price: 174.55 USD (as of 1679692800)  
---  
[Financial Modeling Prep] Price: 174.55 USD (as of 1679692800)

