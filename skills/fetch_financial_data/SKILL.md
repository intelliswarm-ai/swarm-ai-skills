---
name: fetch_financial_data
description: Retrieves recent financial metrics and summary data for a given public company (by ticker symbol) by composing web search and content extraction tools. Attempts to extract data such as price, market cap, P/E ratio, and SEC filings from recent web results.
type: CODE
triggerWhen: When a user requests up-to-date or recent financial metrics, key statistics, or SEC filings for a public company and no direct financial data tool is available.
avoidWhen: When a dedicated financial data API or tool is available; when only historical analysis or non-recent data is needed.
category: data-io
tags: [finance, stock, data-fetch, web, yahoo-finance, sec]
---

# Fetch Financial Data

Retrieves recent financial metrics for a public company using web search and content extraction as a fallback method. Extracts data such as price, market cap, P/E ratio, and SEC filing links from the most relevant and recent search results (e.g., Yahoo Finance, SEC.gov, MarketWatch). Useful when no direct financial data-fetching tool exists.

## Code
```groovy
def ticker = params.get("ticker")?.toUpperCase()
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
```

## References
### - https://finance.yahoo.com/
- https://www.sec.gov/
- Yahoo Finance JSON structure (as seen in page source)

## Resources
### output-template
```
Ticker: {{ticker}}
Yahoo Finance Page: {{yahoo_url}}
Current Price: {{price}}
Market Cap: {{market_cap}}
Trailing P/E: {{pe_ratio}}
52-Week Range: {{fifty_two_week_range}}
Recent SEC Filings:
{{sec_filings}}
```

