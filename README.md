# Jactl POC

A Spring Boot proof-of-concept demonstrating how to embed [Jactl](https://jactl.io) as a safe, sandboxed scripting engine in a JVM application. Includes an interactive browser-based playground.

## What is Jactl?

Jactl is a JVM scripting language designed for **safe embedding**. Unlike Groovy or JavaScript (Nashorn), Jactl scripts have **no access to Java classes by default** — there is no `System.exit()`, no `File`, no `Runtime.exec()`. The host application explicitly opts capabilities *in* rather than trying to block things out.

## Features

- **Interactive playground** — browser UI with a code editor (CodeMirror/Dracula theme), example scripts, and live output
- **REST execution endpoint** — `POST /api/execute` runs a Jactl script and returns output, result, and timing
- **Sandboxing**
  - Language-level: no Java interop, no threads, no I/O unless the host registers it
  - Host-level 5-second timeout — infinite loops are cancelled, not hung
  - 64KB output cap — `println` floods are truncated, not memory-exhausting
  - Per-request isolated globals — scripts cannot share state across runs
- **Host-registered functions** — demonstrates the capability model with two custom functions:
  - `serverInfo()` — returns JVM and OS info
  - `rot13(text)` — string transformation

## Project Structure

```
src/main/java/com/example/jactl/
├── JactlPocApplication.java       # Spring Boot entry point
├── ScriptController.java          # POST /api/execute — runs scripts with timeout & output cap
├── JactlFunctionsConfig.java      # Registers serverInfo() and rot13() into the Jactl runtime
└── UiController.java              # Serves the playground UI at /

src/main/resources/templates/
└── index.html                     # Single-page playground (Thymeleaf + vanilla JS)
```

## Requirements

- Java 21+
- Maven 3.8+

## Running

```bash
mvn spring-boot:run
```

Then open [http://localhost:8080](http://localhost:8080) in a browser.

## API

### `POST /api/execute`

**Request:**
```json
{ "script": "1 + 1" }
```

**Response:**
```json
{
  "success": true,
  "output": "",
  "result": "2",
  "globals": { "request": "{user=demo-user, timestamp=...}" },
  "elapsedMs": 12
}
```

On error or timeout, `success` is `false` and an `error` field describes what went wrong.

## Sandbox Behaviour

| Threat | Protection |
|---|---|
| Java class access (`System.exit`, `File`, etc.) | Compile error — not in the language |
| Infinite loop | 5s wall-clock timeout via `Future.get(timeout)` |
| Output flood | 64KB cap via `BoundedOutputStream` |
| Cross-request state leak | Fresh globals map per request |

## Stack

- Spring Boot 3.3.5
- Jactl 2.1.0
- Thymeleaf (template rendering)
- CodeMirror 5 (browser editor)
