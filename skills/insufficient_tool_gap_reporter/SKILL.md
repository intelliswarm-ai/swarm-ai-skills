---
name: insufficient_tool_gap_reporter
description: Detects and reports when a requested capability cannot be fulfilled due to the absence of an appropriate tool, providing a detailed diagnostic including input parameters and suggestions for next actions.
type: CODE
triggerWhen: When the LLM identifies that no existing tool or skill can fulfill the request or process the input.
avoidWhen: When an appropriate tool or skill is available to fulfill the request.
category: generated
tags: [diagnostics, gap-detection, error-reporting, toolchain]
---

# Insufficient Tool Gap Reporter

This skill is invoked when a request cannot be completed because no suitable tool exists. It generates a clear, structured report for operators or developers, summarizing the input, detected gap, and recommended next steps. This supports rapid troubleshooting and skill development by documenting real-world capability gaps.

## Code
```groovy
def requestedCapability = params.get("requestedCapability") ?: "NO INFORMATION PROVIDED"
def inputParameters = params.get("inputParameters") ?: "NO INPUT PARAMETERS PROVIDED"
def context = params.get("context") ?: "NO CONTEXT PROVIDED"

// Compose a diagnostic report
def report = []
report << "=== INSUFFICIENT TOOL GAP REPORT ==="
report << "Timestamp: " + new Date().toString()
report << "Requested capability: " + requestedCapability
report << "Input parameters: " + inputParameters
report << "Context: " + context
report << ""
report << "Diagnosis: [CONFIRMED] No suitable tool, skill, or integration was found to fulfill the request."
report << ""
report << "Suggested actions:"
report << "1. Review the requested capability and verify if it maps to any existing tools or skills."
report << "2. If this is a new or emerging need, escalate to the skill engineering team for consideration."
report << "3. Consider alternative workflows or data sources."
report << ""
report << "Operator note: This report is auto-generated for skill development and diagnostic purposes."
report.join("\n")
```

## Resources
### output-template
```
=== INSUFFICIENT TOOL GAP REPORT ===
Timestamp: {{timestamp}}
Requested capability: {{requestedCapability}}
Input parameters: {{inputParameters}}
Context: {{context}}

Diagnosis: [CONFIRMED] No suitable tool, skill, or integration was found to fulfill the request.

Suggested actions:
1. Review the requested capability and verify if it maps to any existing tools or skills.
2. If this is a new or emerging need, escalate to the skill engineering team for consideration.
3. Consider alternative workflows or data sources.

Operator note: This report is auto-generated for skill development and diagnostic purposes.
```

