---
name: financial_retriever_web_search
description: Merged super-skill combining capabilities from 3 source skills. Base: Retrieves financial data by directly querying public financial APIs or scraping fallback web sources when built-in retrieval tools are non-functional, enabling essential downstream analysis.. Enhanced with fallback strategies from: financial data web search and calculation, fetch financial data.
type: CODE
triggerWhen: When robust, multi-source financial data is needed with automatic fallback.
avoidWhen: When a single specific data source is required.
category: data-io
tags: [financial, data, retrieval, fallback, api, web, finance, web_search, data_extraction, computation, stock, data-fetch, yahoo-finance, sec, merged, curated]
---

# Financial retriever web search

Merged skill combining the best of 3 source skills:

- **alternative_financial_data_retriever** (score: 89/100, B)
- **financial_data_web_search_and_calculation** (score: 84/100, B)
- **fetch_financial_data** (score: 80/100, B)

## Code
```groovy
// Merged skill: combines 3 source skills
// Base: alternative_financial_data_retriever (score: 89)
// + financial_data_web_search_and_calculation (score: 84)
// + fetch_financial_data (score: 80)

def symbol = params.get("symbol")
def datatype = params.get("datatype") ?: "price" // e.g., "price", "summary", "ratios"

def results = [:]
def errors = []

// Primary: alternative_financial_data_retriever
try {
    def primaryResult = { ->
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
    }.call()
    if (primaryResult && primaryResult.toString().length() > 20) {
        results['primary'] = primaryResult
    }
} catch (Exception e) {
    errors << "primary (alternative_financial_data_retriever) failed: ${e.message}"
}

// Fallback 1: financial_data_web_search_and_calculation
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
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
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_1'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 1 (financial_data_web_search_and_calculation) failed: ${e.message}"
    }
}

// Fallback 2: fetch_financial_data
if (results.isEmpty()) {
    try {
        def fallbackResult = { ->
            if (!ticker || !(ticker ==~ /^[A-Z0-9.]+$/)) {
                return "ERROR: Please provide a valid stock ticker symbol (e.g., AAPL, MSFT)."
            }
            
            // Search for Yahoo Finance summary page
            def searchQuery = URLEncoder.encode(ticker + " stock summary Yahoo Finance", "UTF-8")
            def yahooSearch = tools.web_search.execute(Map.of("query", searchQuery, "numResults", "3"))
            
            // Try to find a Yahoo Finance URL
            def yahooUrlMatcher = (yahooSearch =~ /(https?:\/\/finance\.yahoo\.com\/quote\/[A-Z0-9.]+)/)
            def yahooUrl = yahooUrlMatcher.find() ? yahooUrlMatcher.group(1) : null
            
            def summary = []
            if (yahooUrl) {
                // Extract main statistics from Yahoo Finance page
                def yahooContent = tools.web_content_extract.execute(Map.of("url", yahooUrl))
                summary << "Yahoo Finance Page: $yahooUrl"
                // Extract price, market cap, P/E, and 52-week range with regex
                def priceMatch = (yahooContent =~ /"currentPrice":\{"raw":([\d.]+),/)
                def price = priceMatch.find() ? priceMatch.group(1) : "DATA NOT AVAILABLE"
                def peMatch = (yahooContent =~ /"trailingPE":\{"raw":([\d.]+),/)
                def pe = peMatch.find() ? peMatch.group(1) : "DATA NOT AVAILABLE"
                def mcapMatch = (yahooContent =~ /"marketCap":\{"raw":(\d+)/)
                def mcap = mcapMatch.find() ? mcapMatch.group(1) : "DATA NOT AVAILABLE"
                def rangeMatch = (yahooContent =~ /"fiftyTwoWeekRange":\{"raw":"([\d.]+ - [\d.]+)"/)
                def range = rangeMatch.find() ? rangeMatch.group(1) : "DATA NOT AVAILABLE"
            
                summary << "Current Price: $price"
                summary << "Market Cap: $mcap"
                summary << "Trailing P/E: $pe"
                summary << "52-Week Range: $range"
            } else {
                summary << "Yahoo Finance page not found for ticker $ticker. Search returned:\n" + yahooSearch
            }
            
            // Also search for most recent SEC filings
            def secSearchQuery = URLEncoder.encode(ticker + " site:sec.gov", "UTF-8")
            def secSearch = tools.web_search.execute(Map.of("query", secSearchQuery, "numResults", "3"))
            def secUrlMatcher = (secSearch =~ /(https?:\/\/www\.sec\.gov\/Archives\/edgar\/data\/\d+\/\d+-\d+-\d+\.htm)/)
            def secUrls = []
            while (secUrlMatcher.find()) { secUrls << secUrlMatcher.group(1) }
            if (secUrls) {
                summary << "Recent SEC Filing(s):"
                secUrls.each { summary << "- $it" }
            } else {
                summary << "No recent SEC filings found in top search results."
            }
            
            return summary.join("\n")
        }.call()
        if (fallbackResult && fallbackResult.toString().length() > 20) {
            results['fallback_2'] = fallbackResult
        }
    } catch (Exception e) {
        errors << "fallback 2 (fetch_financial_data) failed: ${e.message}"
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
