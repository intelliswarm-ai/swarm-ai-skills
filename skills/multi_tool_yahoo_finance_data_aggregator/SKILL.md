---
name: multi_tool_yahoo_finance_data_aggregator
description: Aggregates and cross-verifies financial data for a given symbol by querying multiple Yahoo Finance tools, parses their outputs, and returns a unified, confidence-ranked summary. Resolves data access issues by composing existing extraction, parsing, and fallback tools.
type: CODE
triggerWhen: When financial data for a symbol is requested and data accuracy or completeness is critical, especially if direct extraction fails or appears incomplete.
avoidWhen: When only a single data source is required or if the symbol is not recognized by Yahoo Finance.
category: data-io
tags: [finance, data-aggregation, yahoo, extraction, verification]
---

# Multi-Tool Yahoo Finance Data Aggregator

Aggregates and reconciles financial data for a given ticker symbol by querying all available Yahoo Finance tools: the primary extractor, the structured financial parser, and the fallback browser parser. Compares their outputs, notes any discrepancies, and produces a unified summary, indicating data confidence and listing any missing or conflicting fields.

## Code
```groovy
def symbol = params.get("symbol")
if (!symbol) return "ERROR: 'symbol' parameter required."

def results = [:]
def errors = []

// Run all available Yahoo Finance data tools
try {
    results["extractor"] = tools.yahoo_finance_data_extractor.execute(Map.of("symbol", symbol))
} catch (Exception e) {
    errors << "yahoo_finance_data_extractor failed: " + e.getMessage()
}
try {
    results["structured"] = tools.yahoo_finance_structured_financial_parser.execute(Map.of("symbol", symbol))
} catch (Exception e) {
    errors << "yahoo_finance_structured_financial_parser failed: " + e.getMessage()
}
try {
    results["fallback"] = tools.yahoo_finance_fallback_browser_parser.execute(Map.of("symbol", symbol))
} catch (Exception e) {
    errors << "yahoo_finance_fallback_browser_parser failed: " + e.getMessage()
}

// Parse JSON if possible
def slurper = new groovy.json.JsonSlurper()
def parsed = [:]
results.each { k, v ->
    try {
        parsed[k] = slurper.parseText(v)
    } catch (Exception e) {
        parsed[k] = v  // Not JSON, keep as raw string
    }
}

// Field-wise reconciliation
def fields = ["marketCap", "price", "peRatio", "dividendYield", "currency"]
def summary = [:]
fields.each { field ->
    def values = []
    parsed.each { src, data ->
        if (data instanceof Map && data[field] != null) {
            values << [src: src, value: data[field]]
        }
    }
    if (values.size() == 0) {
        summary[field] = [status: "MISSING", detail: "Not found in any source"]
    } else if (values.collect { it.value }.unique().size() == 1) {
        summary[field] = [status: "CONSISTENT", value: values[0].value, sources: values.collect{it.src}]
    } else {
        summary[field] = [status: "CONFLICT", values: values]
    }
}

// Build output
def output = new StringBuilder()
output << "Yahoo Finance Data Aggregation for: " + symbol + "\n"
output << (errors ? "Errors encountered: " + errors.join("; ") + "\n" : "")
fields.each { field ->
    def s = summary[field]
    if (s.status == "CONSISTENT") {
        output << "${field}: ${s.value} [${s.status}, from ${s.sources.join(", ")}]\n"
    } else if (s.status == "CONFLICT") {
        output << "${field}: CONFLICT among sources:\n"
        s.values.each { v -> output << "  - ${v.src}: ${v.value}\n" }
    } else {
        output << "${field}: NOT FOUND in any source\n"
    }
}
return output.toString()
```

## Resources
### output-template
```
Yahoo Finance Data Aggregation for: {{symbol}}
{{errors}}
marketCap: {{marketCap}}
price: {{price}}
peRatio: {{peRatio}}
dividendYield: {{dividendYield}}
currency: {{currency}}
```

