---
name: auto_tool_composer_wrapper
description: Wraps the auto_tool_composer tool to enable dynamic composition of available tools based on parameters, returning the composed tool's result for advanced data processing or multi-step operations.
type: CODE
triggerWhen: When a task requires chaining or orchestrating multiple tools in sequence, or when no single existing tool fulfills the task and dynamic composition is needed.
avoidWhen: When a single existing tool can directly solve the problem without composition, or when the operation can be handled by simple instructions.
category: generated
tags: [tool-orchestration, auto-tool-composer, composition, workflow, dynamic]
---

# Auto Tool Composer Wrapper

This skill wraps the `auto_tool_composer` tool, enabling dynamic composition and execution of available tools based on a provided parameter set. It is designed for situations where no single tool is sufficient, and a workflow or pipeline of multiple tools must be constructed and executed programmatically. This skill acts as a general-purpose orchestrator, allowing flexible, parameter-driven invocation and chaining of tools.

## Code
```groovy
// Accepts 'param' as a required parameter, describing the desired tool composition or workflow.
// Example: params = [param: "search web for latest CVEs, then summarize top 3"]
def param = params.get("param")
if (!param || !(param instanceof String) || param.trim().isEmpty()) {
    return "ERROR: 'param' input is required and must be a non-empty string describing the desired tool composition."
}
def result = tools.auto_tool_composer.execute(Map.of("param", param))
if (!result || !(result instanceof String) || result.trim().isEmpty()) {
    return "ERROR: auto_tool_composer returned no result for the given param."
}
return result
```

## Resources
### output-template
```
Result of tool composition for "{{param}}":
{{result}}
```

