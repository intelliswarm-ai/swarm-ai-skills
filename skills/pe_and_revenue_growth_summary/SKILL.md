---
name: pe_and_revenue_growth_summary
description: Composes available financial computation tools to generate a structured summary of a company's Price/Earnings ratio and revenue growth trends, extracting and combining results from existing skill outputs for downstream analysis or reporting.
type: CODE
triggerWhen: When a structured summary of both P/E ratio and revenue growth is needed, especially for financial reporting or investment analysis, and Yahoo Finance data extraction is unavailable.
avoidWhen: When detailed, raw financial data or browsing capability is required, or when only a single metric is needed.
category: analysis
tags: [finance, computation, summary, pe_ratio, revenue_growth]
---

# P/E and Revenue Growth Summary

## Code
```groovy
def ticker = params.get("ticker")
if (!ticker) return "ERROR: 'ticker' parameter required."

// Compose the pe_and_revenue_growth_calculator tool (assumes tool returns both metrics in output)
def rawOutput = tools.pe_and_revenue_growth_calculator.execute(Map.of("ticker", ticker))

// Parse output for structured summary (assume tool returns something like: "P/E: 23.4\nRevenue Growth: 12.3%")
def peMatch = (rawOutput =~ /P\/E:\s*([\d\.]+)/)
def revMatch = (rawOutput =~ /Revenue Growth:\s*([\d\.\-%]+)/)

def peValue = peMatch ? peMatch[0][1] : "DATA NOT AVAILABLE"
def revValue = revMatch ? revMatch[0][1] : "DATA NOT AVAILABLE"

def summary = "Financial Summary for ${ticker}:\n" +
              "- Price/Earnings (P/E) Ratio: ${peValue}\n" +
              "- Revenue Growth: ${revValue}"

return summary
```

## Resources
### output-template
```
Financial Summary for {{ticker}}:
- Price/Earnings (P/E) Ratio: {{peValue}}
- Revenue Growth: {{revValue}}
```

