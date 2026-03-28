---
name: extract_company_financials_robust
description: Attempts multiple web-based toolchains to extract basic company financials (revenue, profit, valuation, etc) from well-known sources, parsing and consolidating results for reliability in cases where standard web search mechanisms fail or return incomplete data.
type: CODE
triggerWhen: When company financial data is requested and prior attempts (including browser or web search fallback) have not produced structured or satisfactory results.
avoidWhen: When high-quality, structured company financials are already available or the company is highly obscure/private with no public data.
category: data-io
tags: [finance, web-scraping, data-fusion, company-financials, extraction]
---

# Extract Company Financials Robustly

Attempts to retrieve and parse basic company financials (revenue, profit, valuation, etc.) for a given company by composing multiple robust financial data extraction tools and web searchers. Consolidates and merges results to maximize completeness and reliability, mitigating the failures of individual search/fallback methods.

## Code
```groovy
def company = params.get("company")?.toString()?.trim()
if (!company) return "[ERROR] No company specified."

def results = []
def errors = []

// 1. Try robust_financial_data_extractor first (likely best structured output)
try {
    def robustRaw = tools.robust_financial_data_extractor.execute(Map.of("company", company))
    if (robustRaw && robustRaw.trim().length() > 0) {
        // Try to extract JSON if possible
        def jsonCandidate = robustRaw.find(/\{[\s\S]+?\}/)
        if (jsonCandidate) {
            try {
                def data = new groovy.json.JsonSlurper().parseText(jsonCandidate)
                results << [source: "robust_financial_data_extractor", data: data]
            } catch (Exception e) {
                // Not JSON, just keep raw
                results << [source: "robust_financial_data_extractor", data: robustRaw]
            }
        } else {
            results << [source: "robust_financial_data_extractor", data: robustRaw]
        }
    }
} catch (Exception e) {
    errors << "robust_financial_data_extractor failed: " + e.getMessage()
}

// 2. Also try financial_data_web_search_and_calculation for redundancy
try {
    def webRaw = tools.financial_data_web_search_and_calculation.execute(Map.of("company", company))
    if (webRaw && webRaw.trim().length() > 0) {
        // Try to extract JSON if possible
        def jsonCandidate = webRaw.find(/\{[\s\S]+?\}/)
        if (jsonCandidate) {
            try {
                def data = new groovy.json.JsonSlurper().parseText(jsonCandidate)
                results << [source: "financial_data_web_search_and_calculation", data: data]
            } catch (Exception e) {
                results << [source: "financial_data_web_search_and_calculation", data: webRaw]
            }
        } else {
            results << [source: "financial_data_web_search_and_calculation", data: webRaw]
        }
    }
} catch (Exception e) {
    errors << "financial_data_web_search_and_calculation failed: " + e.getMessage()
}

// 3. If both failed or results look empty, try auto_tool_composer as last resort
if (results.size() == 0 || results.every { (it.data instanceof String) && it.data.trim().isEmpty() }) {
    try {
        def autoRaw = tools.auto_tool_composer.execute(Map.of("query", company + " latest revenue profit valuation"))
        if (autoRaw && autoRaw.trim().length() > 0) {
            results << [source: "auto_tool_composer", data: autoRaw]
        }
    } catch (Exception e) {
        errors << "auto_tool_composer failed: " + e.getMessage()
    }
}

// 4. Merge and deduplicate by key info
def merged = [:]
results.each { entry ->
    def data = entry.data
    if (data instanceof Map) {
        data.each { k, v ->
            if (v && !merged.containsKey(k)) merged[k] = [v, entry.source]
        }
    } else if (data instanceof String) {
        // Try to extract key financials with regex
        def patts = [
            "revenue": ~/([Rr]evenue)[^\d\$]{0,10}([\$\d\.,]+[MBT]?)\b/,
            "profit": ~/([Nn]et (income|profit|earnings))[^\d\$]{0,10}([\$\d\.,]+[MBT]?)\b/,
            "valuation": ~/([Vv]aluation|[Mm]arket ?[Cc]ap)[^\d\$]{0,10}([\$\d\.,]+[MBT]?)\b/
        ]
        patts.each { key, patt ->
            def m = data =~ patt
            if (m && m[0] && m[0][2]) {
                if (!merged.containsKey(key)) merged[key] = [m[0][2], entry.source]
            }
        }
    }
}

// 5. Prepare output
def output = new StringBuilder()
output << "# Financials for ${company}\n"
if (merged.size() == 0) {
    output << "No structured financial data found.\n"
    if (errors.size() > 0) output << "Errors: " + errors.join("; ") + "\n"
    output << "Raw Results:\n"
    results.each { entry ->
        output << "- [${entry.source}] ${entry.data.toString().take(500)}\n"
    }
} else {
    merged.each { k, val ->
        output << "- ${k.capitalize()}: ${val[0]} [source: ${val[1]}]\n"
    }
    if (errors.size() > 0) output << "\nErrors: " + errors.join("; ") + "\n"
}
return output.toString()
```

## Resources
### output-template
```
# Financials for {{company}}
- Revenue: {{revenue}} [source: {{revenue_source}}]
- Profit: {{profit}} [source: {{profit_source}}]
- Valuation: {{valuation}} [source: {{valuation_source}}]
...
(Include errors and raw data if no structured info found)
```

## Examples
### Example 1
**Input:** company = "Salesforce"
**Expected Output:** # Financials for Salesforce  
- Revenue: $34.9B [source: robust_financial_data_extractor]  
- Profit: $4.1B [source: robust_financial_data_extractor]  
- Valuation: $200B [source: financial_data_web_search_and_calculation]

