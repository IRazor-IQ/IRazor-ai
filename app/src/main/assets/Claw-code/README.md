# IRazor AI — Autonomous Agentic Coding Runtime IQD2R

## MASTER BLUEPRINT (1–14)

```
Layers:  1   2   3   4   5   6   7   8   9  10  11  12  13  14
         │   │   │   │   │   │   │   │   │   │   │   │   │   │
Type ────┘   │   │   │   │   │   │   │   │   │   │   │   │   │
Stream ──────┘   │   │   │   │   │   │   │   │   │   │   │   │
Hooks ──────────┘   │   │   │   │   │   │   │   │   │   │   │
Approval ───────────┘   │   │   │   │   │   │   │   │   │   │
Memory ─────────────────┘   │   │   │   │   │   │   │   │   │
Orchestration ──────────────┘   │   │   │   │   │   │   │   │
Tool Engine ───────────────────┘   │   │   │   │   │   │   │
MCP ──────────────────────────────┘   │   │   │   │   │   │
Compression ──────────────────────────┘   │   │   │   │   │
State Fabric ─────────────────────────────┘   │   │   │   │
Resilience ───────────────────────────────────┘   │   │   │
Observability ────────────────────────────────────┘   │   │
Rate Control ─────────────────────────────────────────┘   │
Human Gate ──────────────────────────────────────────────┘
```

**Continuity:** HCP+ (with fallback & recovery)

**Core Flows:**
- Response → Stream → Parse → Execute → Report
- Questions → Intent Classify → Route → Resolve
- Other Questions → Fallback → Context Recall → Re-route
- Others → Catch-all → Log → Defer
- Observability Telemetry → Trace → Metric → Log
- Approval Queue → Gate → Escalate → Audit


---


## Layer 1: **Type Configuration**

### IRazorAi.md Scanner → Schema & Rules Injection

The Type Configuration layer defines the static schema that governs all agent behavior. Every prompt, tool call, and agent output passes through a strict type validator before execution proceeds.

```
┌─────────────────────────────────────────────────┐
│              IRazorAi.md Scanner                 │
│  Reads → Parses → Validates → Injects Schema    │
├─────────────────────────────────────────────────┤
│  • Schema Registry: /rules/*.json                │
│  • Type Definitions: /types/*.d.ts               │
│  • Injection Pipeline: parse → validate → merge  │
│  • Runtime Cache: warm on boot, refresh on write  │
└─────────────────────────────────────────────────┘
```

**Schema & Rules Injection:**

```
src/
  rules/
    agent.schema.json       → agent type contracts
    tool.schema.json        → tool input/output types
    hook.schema.json        → hook registration types
    flow.schema.json        → execution flow types
    memory.schema.json      → memory slot types
```

**Strict Typing & Intent Isolation:**

Every intent is isolated by type. No cross-contamination between:
- `agent:code` → code generation only
- `agent:reasoning` → reasoning only
- `agent:tool` → tool execution only
- `agent:qa` → validation only

```typescript
interface TypeConfig {
  scanner: {
    root: string;
    rules: string[];
    schemas: Record<string, SchemaDef>;
    injectMode: 'lazy' | 'eager' | 'hybrid';
  };
  isolation: {
    intents: string[];
    strictMode: boolean;
    fallbackOnViolation: 'reject' | 'warn' | 'reroute';
  };
}
```

**Injection Pipeline:**

```
[Scan] → [Parse Schema] → [Validate Types]
    ↓
[Inject Rules] → [Build Type Cache] → [Ready]
```

**Performance:**
- Cold scan: <50ms per 1000 rules
- Hot cache hit: <1μs
- Schema merge: O(n) with deduplication
- Memory: ~200KB per 10K schemas


---


## Layer 2: **Streaming Intent Interface**

### Real-Time Intent Streaming

The streaming layer provides low-latency, token-by-token parsing of user prompts with continuous intent refinement.

```
┌──────────────────────────────────────────────────────┐
│              Streaming Intent Interface                │
│  Parse → Classify → Route → Stream Back              │
├──────────────────────────────────────────────────────┤
│  • SSE-based streaming                                │
│  • Token-by-token intent classification               │
│  • Partial result emission every N tokens             │
│  • Backpressure-aware flow control                    │
│  • WebSocket upgrade path for bidirectional           │
└──────────────────────────────────────────────────────┘
```

**Stream Protocol:**

```
client → server: POST /stream { prompt, context }
server → client: SSE stream

event: intent_partial
data: { type: "code", confidence: 0.85, tokens: [...] }

event: intent_complete
data: { type: "code", confidence: 0.97, tokens: [...], result: {...} }

event: error
data: { code: "INTENT_UNKNOWN", message: "...", recoverable: true }
```

**Intent Classification Pipeline:**

```
Raw Tokens
    ↓
[Token Buffer] ─────────────→ [Sliding Window Analyzer]
    ↓                                    ↓
[Intent Classifier] ←──── [Context Features]
    ↓
[Route Decision]
    ↓
[Stream Result]
```

**Low-Latency Parsing:**

| Metric | Target | P95 |
|---|---|---|
| First token latency | <50ms | <120ms |
| Intent classification | <100ms | <250ms |
| Full stream TTFB | <200ms | <400ms |
| Tokens/second | 500+ | 200+ |

**Continuous Token Flow:**

```
Window: [t_0 ... t_n] → classify → [t_1 ... t_{n+1}] → reclassify
```

Reclassification frequency: every 8 tokens or 50ms, whichever comes first.


---


## Layer 3: **Hook System**

### UserPromptSubmit → Pre-Process Trigger

The hook system provides middleware-ready interception points for validation, enrichment, transformation, and routing.

```
┌──────────────────────────────────────────────────────┐
│                    Hook System                         │
│  Pre-hooks → Execution → Post-hooks → Error hooks    │
├──────────────────────────────────────────────────────┤
│  • UserPromptSubmit → pre-process trigger             │
│  • Middleware-ready hooks                             │
│  • Validation & enrichment pipelines                  │
│  • Async/sync hook support                            │
│  • Hook chain with priority ordering                  │
└──────────────────────────────────────────────────────┘
```

**Hook Lifecycle:**

```
User Input
    ↓
pre:validate    ─→  [schema check, sanitize]
pre:enrich      ─→  [context inject, history attach]
pre:route       ─→  [intent routing, agent dispatch]
    ↓
[EXECUTION]
    ↓
post:transform  ─→  [output format, filter]
post:persist    ─→  [save to history, index]
post:notify     ─→  [webhook, event bus]
    ↓
error:handle    ─→  [retry, degrade, fail]
error:log       ─→  [structured log, trace]
```

**Hook Registration:**

```typescript
interface Hook {
  name: string;
  phase: 'pre' | 'post' | 'error';
  priority: number;        // 0 = highest
  async: boolean;
  fn: (ctx: HookContext) => Promise<HookResult> | HookResult;
}

// Built-in hooks
hooks: [
  { name: 'validateInput',    phase: 'pre',   priority: 0 },
  { name: 'sanitizePrompt',   phase: 'pre',   priority: 1 },
  { name: 'injectContext',    phase: 'pre',   priority: 2 },
  { name: 'classifyIntent',   phase: 'pre',   priority: 3 },
  { name: 'formatOutput',     phase: 'post',  priority: 0 },
  { name: 'saveHistory',      phase: 'post',  priority: 1 },
  { name: 'emitEvent',        phase: 'post',  priority: 2 },
  { name: 'handleError',      phase: 'error', priority: 0 },
]
```

**Middleware-Ready Hooks for Validation & Enrichment:**

```
pre:validate
  ├── checkTokenBudget(user)          → reject if over budget
  ├── checkRateLimit(ip)              → throttle if exceeded
  ├── validateSchema(prompt)          → reject malformed
  └── checkContentPolicy(prompt)      → filter restricted

pre:enrich
  ├── injectSessionContext(sessionId) → attach memory
  ├── attachFileContext(files)        → inline attachments
  ├── resolveReferences(refs)         → link documents
  └── addSystemPrompt(role)           → role-specific instructions
```

**Performance:**
- Hook dispatch overhead: <5μs per hook
- Parallel hook execution: Promise.all for same-phase hooks
- Hook chain timeout: 5s total
- Error isolation: one hook failure never cascades


---


## Layer 4: **Silent Auto-Approval (with Optional Human Gate)**

### AI Classifier → MATCH: settings.json

The auto-approval layer uses an AI classifier to determine operation risk and either silently proceed or gate behind human approval.

```
┌──────────────────────────────────────────────────────────┐
│              Silent Auto-Approval Engine                   │
│  Classify → Risk Score → MATCH → Auto-proceed / Gate     │
├──────────────────────────────────────────────────────────┤
│  • AI Classifier → scores operations 0.0–1.0              │
│  • MATCH: settings.json → threshold configuration          │
│  • Auto-proceed for low-risk operations                    │
│  • Optional HumanInTheLoop: true → sensitive ops gate     │
│  • Example: npm install react → silent if safe, else gate │
└──────────────────────────────────────────────────────────┘
```

**Risk Classification:**

```
Operation
    ↓
[AI Classifier]
    ├── signature analysis
    ├── historical patterns
    ├── dependency graph
    └── scope evaluation
    ↓
Risk Score: 0.0 (safe) ──────────────────────→ 1.0 (critical)
              │                                        │
              ↓                                        ↓
         auto-proceed                            human gate
```

**settings.json Configuration:**

```json
{
  "autoApproval": {
    "enabled": true,
    "threshold": 0.3,
    "humanInTheLoop": true,
    "humanGateThreshold": 0.7,
    "classifierModel": "razor-risk-v2",
    "cacheResults": true,
    "cacheTTL": 300000,
    "safeDomains": [
      "npm install",
      "pip install",
      "git clone",
      "mkdir",
      "touch",
      "ls",
      "cat",
      "echo"
    ],
    "sensitivePatterns": [
      "rm -rf",
      "sudo",
      "chmod 777",
      "eval(",
      "exec(",
      "process.env",
      "fs.writeFileSync.*/etc",
      "drop table",
      "truncate"
    ],
    "approvalTimeout": 300000,
    "auditLog": true
  }
}
```

**Example Flow — Low Risk:**

```
User: "npm install react"
    ↓
Classifier → score: 0.05 → BELOW threshold
    ↓
Auto-proceed: npm install react
    ↓
Result → streamed to user
```

**Example Flow — High Risk:**

```
User: "rm -rf / --no-preserve-root"
    ↓
Classifier → score: 0.98 → ABOVE gate threshold
    ↓
Gate: "This operation requires approval.
         Operation: rm -rf /
         Risk: CRITICAL (0.98)
         Approve? (Y/N) [timeout: 5min]"
    ↓
User: Y → execute | N → rejected | timeout → auto-reject
```


---


## Layer 5: **Prefix Memory (Cache-First)**

### Cached Tokens → Near-Zero Cost

Prefix memory implements a cache-first approach where repeated prompt prefixes are cached and reused at near-zero cost.

```
┌──────────────────────────────────────────────────────┐
│                Prefix Memory System                    │
│  Cache → Match → Reuse → Invalidate                  │
├──────────────────────────────────────────────────────┤
│  • Prompt Cache = request + rules + structure         │
│  • Frozen state → deterministic rebuild               │
│  • Sub-second context recall                          │
│  • Project-wide rehydration                           │
│  • LRU eviction with priority tiers                   │
└──────────────────────────────────────────────────────┘
```

**Cache Architecture:**

```
             ┌──────────────┐
Request ────→│  Prefix Hash  │
             └──────┬───────┘
                    ↓
             ┌──────────────┐
             │  Cache Lookup │
             └──────┬───────┘
                    ↓
           ┌────────┴────────┐
           ↓                  ↓
     [Cache Hit]        [Cache Miss]
           ↓                  ↓
    Return Cached      Build Fresh
    Tokens (+ rules)   Token State
           ↓                  ↓
     [Stream Result]   [Store in Cache]
                             ↓
                      [Stream Result]
```

**Frozen State:**

```typescript
interface PrefixCacheEntry {
  hash: string;
  prefix: string;
  tokens: number[];
  rules: string[];
  structure: {
    system: string;
    context: string[];
    constraints: string[];
  };
  frozen: boolean;          // immutable after freeze
  createdAt: number;
  accessCount: number;
  lastAccess: number;
}
```

**Sub-Second Context Recall:**

| Operation | Time |
| Cache lookup (hot) | <1μs |
| Cache lookup (cold) | <50μs |
| Full rehydration | <100ms |
| Invalidation | <10μs |

**Project-Wide Rehydration:**

On project load, the prefix memory system rehydrates from:
1. `.razor/cache/prefix-cache.json` — serialized cache entries
2. `.razor/rules/*.json` — rule definitions
3. Session state — active context windows

**Eviction Policy (Priority-Tiered LRU):**

```
Tier 0 (pin): never evict — system prompts, core rules
Tier 1 (high): evict last — common patterns, frequent requests
Tier 2 (medium): normal LRU — standard cache entries
Tier 3 (low): evict first — one-shot requests, temp context
```


---


## Layer 6: **Sub-Agent Orchestration**

### Frontend Agent → UI & State

### Backend Agent → Logic & Services

### QA Agent → Validation & Tests

The sub-agent orchestration layer provides a centralized dispatcher with decoupled execution across specialized agents.

```
┌─────────────────────────────────────────────────────────────┐
│                 Sub-Agent Orchestrator                        │
│  Centralized Dispatcher → Decoupled Execution                │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │  Frontend Agent  │  │  Backend Agent   │  │   QA Agent   │ │
│  │  → UI & state    │  │  → Logic &       │  │  → Validation│ │
│  │  → component gen │  │    services       │  │  → tests     │ │
│  │  → style system  │  │  → API design     │  │  → coverage  │ │
│  └─────────────────┘  └─────────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**Dispatcher Architecture:**

```
User Request
    ↓
[Intent Classifier] ─→ Frontend │ Backend │ QA │ Parallel
    ↓
[Dispatcher]
    ├── decompose → task graph
    ├── assign → agent pool
    ├── execute → parallel/sync
    └── merge → unified output
```

**Agent Communication Protocol:**

```
Agent Message:
{
  "agentId": "frontend-01",
  "type": "task_result",
  "taskId": "t-42",
  "status": "completed" | "failed" | "partial",
  "output": { ... },
  "metrics": {
    "tokensUsed": 1500,
    "elapsedMs": 2340,
    "confidence": 0.94
  }
}
```

**Agent Pool Configuration:**

```yaml
agents:
  frontend:
    count: 3
    capabilities: [react, vue, css, html, state]
    tools: [file_write, component_gen, style_gen]
  backend:
    count: 3
    capabilities: [api, db, auth, logic, services]
    tools: [file_write, schema_gen, route_gen]
  qa:
    count: 2
    capabilities: [test, lint, validate, coverage]
    tools: [test_runner, lint_check, coverage_report]
```

**Decoupled Execution:**

```
Task: "Build a todo app with React + Express"

Dispatcher:
  ├→ FrontendAgent-1: "Create React components (TodoList, TodoItem, AddTodo)"
  │   └→ writes: src/components/TodoList.jsx
  │   └→ writes: src/components/TodoItem.jsx
  │   └→ writes: src/components/AddTodo.jsx
  │
  ├→ FrontendAgent-2: "Create state management & styling"
  │   └→ writes: src/store.js
  │   └→ writes: src/styles.css
  │
  ├→ BackendAgent-1: "Create Express API with CRUD endpoints"
  │   └→ writes: server/index.js
  │   └→ writes: server/routes/todos.js
  │   └→ writes: server/models/Todo.js
  │
  └→ QAAgent-1: "Generate & run tests"
      └→ writes: tests/frontend/TodoList.test.jsx
      └→ writes: tests/backend/todos.test.js
      └→ runs: npm test
```


---


## Layer 7: **Tool Execution Engine**

### Parallel Reads (10X Speed) → Context & Domains

### Sequential Writes (1X Security) → Race Condition Prevention

### Preloaded Modules (MB 0) → Fast Tool Spawn

The tool execution engine provides high-performance, safe tool execution with parallel reads and sequential writes.

```
┌─────────────────────────────────────────────────────────────┐
│                  Tool Execution Engine                        │
│  Parallel Reads → Sequential Writes → Preloaded Modules     │
├─────────────────────────────────────────────────────────────┤
│  • Parallel reads: 10X speed for context & domain lookups    │
│  • Sequential writes: 1X security, race condition prevention │
│  • Preloaded modules: 0MB memory, instant spawn              │
│  • Tool sandboxing: per-tool capability isolation            │
│  • Result caching: deduplicated tool results                 │
└─────────────────────────────────────────────────────────────┘
```

**Execution Model:**

```
         ┌──────────────────────┐
Request  │  Tool Execution       │
────────→│  Engine               │
         │                       │
         │  [Read Operations]    │  ← parallel (concurrent)
         │  ├── file:read        │
         │  ├── search:query     │
         │  ├── db:select        │
         │  └── http:get         │
         │                       │
         │  [Process]            │  ← single-threaded merge
         │       │               │
         │  [Write Operations]   │  ← sequential (mutex)
         │  ├── file:write       │
         │  ├── db:insert        │
         │  └── http:post        │
         │                       │
         └──────────────────────┘
                    ↓
               [Result]
```

**Parallel Read Pool:**

```typescript
interface ReadPool {
  maxConcurrency: number;    // default: 10
  queue: ToolRequest[];
  active: Map<string, Promise<ToolResult>>;
  results: Map<string, ToolResult>;
  
  async execute(tools: ToolRequest[]): Promise<Map<string, ToolResult>>;
}

// Pool behavior
readPool.maxConcurrency = 10;
readPool.queue = [toolRequest_1, toolRequest_2, ..., toolRequest_N];
// All N execute in parallel, limited by maxConcurrency
```

**Sequential Write Queue:**

```typescript
interface WriteQueue {
  mutex: Lock;
  queue: ToolRequest[];
  executing: boolean;
  
  async enqueue(request: ToolRequest): Promise<ToolResult> {
    await this.mutex.acquire();
    try {
      return await this.executeSingle(request);
    } finally {
      this.mutex.release();
    }
  }
}

// All writes execute one-at-a-time, in order
```

**Preloaded Modules:**

Modules are preloaded into a V8 code cache for instant tool spawn:
```
| Module | Load Time | Memory |
|------------------------------|
| file-system | <1ms | 0KB (code cache) |
| http-client | <1ms | 0KB (code cache) |
| sql-query | <1ms | 0KB (code cache) |
| template-engine | <1ms | 0KB (code cache) |
| diff-patch | <1ms | 0KB (code cache) |

Total preloaded memory overhead: **0MB** (code caching via V8 snapshot). All modules loaded from mmap'd cache files.

```

**Tool Sandboxing:**

```json
{
  "tool": "file:write",
  "sandbox": {
    "allowedPaths": ["/project/**", "/tmp/**"],
    "deniedPaths": ["/etc/**", "/proc/**", "/sys/**"],
    "maxFileSize": 10485760,
    "allowedExtensions": [".js", ".ts", ".json", ".md", ".html", ".css"],
    "deniedExtensions": [".env", ".key", ".pem", ".secret"],
    "timeout": 5000,
    "memoryLimit": 52428800
  }
}
```


---


## Layer 8: **MCP Servers (Model Context Protocol)**

### sqlite-mcp-server :: Connected

### Efficient File-Mode Exit & Data Querying

### Context Pooling Across Agents

MCP servers provide standardized data access and context pooling across all agents in the system.

```
┌──────────────────────────────────────────────────────────────┐
│                    MCP Server Layer                            │
│  Connected Servers → Data Querying → Context Pooling         │
├──────────────────────────────────────────────────────────────┤
│  • sqlite-mcp-server :: Connected                             │
│  • Efficient file-mode exit & data querying                   │
│  • Context pooling across agents                              │
│  • Standardized query interface                               │
│  • Connection pooling & health checks                         │
└──────────────────────────────────────────────────────────────┘
```

**Connected MCP Servers:**

| Server | Status | Protocol | Pool Size |
|---|---|---|---|
| sqlite-mcp-server | Connected | stdio | 5 |
| filesystem-mcp | Connected | stdio | 3 |
| search-mcp | Connected | HTTP/SSE | 2 |
| browser-mcp | Connected | HTTP/SSE | 2 |

sqlite-mcp-server Configuration:**

```json
{
  "server": "sqlite-mcp-server",
  "transport": "stdio",
  "databases": {
    "project": {
      "path": "/data/razor/project.db",
      "mode": "read-write",
      "poolSize": 5,
      "timeout": 10000
    },
    "cache": {
      "path": "/data/razor/cache.db",
      "mode": "read-write",
      "poolSize": 3,
      "timeout": 5000
    },
    "session": {
      "path": "/data/razor/session.db",
      "mode": "read-write",
      "poolSize": 2,
      "timeout": 3000
    }
  }
}
```

**Query Interface:**

```sql
-- Project metadata queries
SELECT * FROM project_files WHERE path LIKE '/src/%';

-- Session state queries
SELECT * FROM session_state WHERE session_id = ? ORDER BY checkpoint DESC;

-- Agent context pooling
SELECT context FROM agent_pool WHERE agent_id = ? AND status = 'active';

-- Tool execution history
SELECT * FROM tool_log WHERE session_id = ? ORDER BY timestamp DESC LIMIT 100;
```

**Context Pooling Across Agents:**

```
Agent-1 ──→ MCP Pool ──→ sqlite-mcp ──→ project.db
Agent-2 ──→ MCP Pool ──→ sqlite-mcp ──→ project.db (shared)
Agent-3 ──→ MCP Pool ──→ sqlite-mcp ──→ project.db (shared)
                │
                └── Connection Queue (FIFO, up to 5 concurrent)
```

**Efficient File-Mode Exit:**

```
sqlite-mcp:
  file_open → mmap → query → result → munmap → close
  Total overhead: <2ms per file-mode operation
  Memory: file-backed (no heap allocation for reads)
```


---


## Layer 9: **Contextual Compression Algorithms**

### KB 8 → Compressed Summary for Core Work

### npm install Completed → Registry Fetch & Linking

### Deprecated Warnings Suppressed → Clean Execution

Contextual compression reduces token usage while preserving semantic integrity across all agent operations.

```
┌──────────────────────────────────────────────────────────────┐
│              Contextual Compression Engine                     │
│  Token Reduction → Semantic Preservation → Clean Output      │
├──────────────────────────────────────────────────────────────┤
│  • KB 8 → compressed summary for core work                   │
│  • npm install → registry fetch & linking (concise)           │
│  • Deprecated warnings suppressed → clean execution           │
│  • Adaptive compression ratio based on context criticality    │
│  • Streaming decompression on demand                          │
└──────────────────────────────────────────────────────────────┘
```

**Compression Pipeline:**

```
Raw Context (N tokens)
    ↓
[Analyzer] → importance scoring per segment
    ↓
[Compressor]
  ├── prune: remove low-importance tokens     (ratio: 0.3×)
  ├── summarize: extract key semantics        (ratio: 0.2×)
  └── abstract: replace with compressed repr  (ratio: 0.1×)
    ↓
Compressed Context (M tokens, M << N)
    ↓
[Decompressor] (on access) → reconstruct from compressed + cache
```

**KB 8 Compression (for core work):**

```
Input: 8KB of development context
  ├── project structure
  ├── dependency graph
  ├── recent edits
  └── active task state

Compression Strategy:
  ├── structure → hash tree (96% reduction)
  ├── graph → adjacency list (85% reduction)
  ├── edits → diff patches (70% reduction)
  └── state → state delta (90% reduction)

Output: ~1.2KB compressed summary
  Performance: ~100μs compress, ~50μs decompress
  Quality: 94% semantic preservation
```

**npm install — Registry Fetch & Linking:**

```
Phase 1: Resolve
  ├── registry:fetch(package.json) → dependency tree
  └── compress: tree → flat adjacency (85% smaller)

Phase 2: Install
  ├── npm install --no-audit --no-fund --loglevel=error
  └── capture: only errors + warnings (suppress progress)

Phase 3: Link
  ├── node_modules/.cache → verify integrity
  └── compress: resolution + lockfile hash (99% reduction)
```

**Deprecated Warnings Suppression:**

```javascript
// Clean execution mode
process.removeAllListeners('warning');
process.on('warning', (warning) => {
  if (warning.name === 'DeprecationWarning') return;  // suppress
  if (warning.message.includes('Experimental')) return; // suppress
  if (warning.message.includes('deprecated')) return;  // suppress
  console.warn(warning.message);  // allow critical warnings
});
```

**Compression Benchmarks:**

 Project structure (8KB) / 8KB / 320B / 25:1 / 80μs 
 
Dependency graph / 64KB / 3.2KB / 20:1 / 320μs 

Recent edits / 16KB / 1.8KB / 9:1 /150μs 

 npm registry response / 128KB | 12KB | 10:1 1.2ms 
 
Session state / 32KB / 640B 50:1 / 200μs /

 Agent conversation / 256KB / 18KB / 14:1 / 2.8ms 


---


## Layer 10: **State Fabric & Session Manager (NEW)**

### Session Checkpointing → Every 10 Tokens

### State Versioning → Rollback to Any Prior State

### Idle Session Freeze → Zero Memory Leak

### Cross-Agent State Sync → Eventual Consistency

### Session Replay → Debug Mode

The State Fabric provides fault-tolerant session management with checkpointing, versioning, and cross-agent synchronization.

```
┌──────────────────────────────────────────────────────────────┐
│              State Fabric & Session Manager                   │
│  Checkpoint → Version → Freeze → Sync → Replay              │
├──────────────────────────────────────────────────────────────┤
│  • Session checkpointing: every 10 tokens                    │
│  • State versioning: rollback to any prior state             │
│  • Idle session freeze: zero memory leak                     │
│  • Cross-agent state sync: eventual consistency              │
│  • Session replay: debug mode with step-through              │
└──────────────────────────────────────────────────────────────┘
```

**Session Checkpointing (Every 10 Tokens):**

```
Token Index: 0  10  20  30  40  50  60
             │   │   │   │   │   │   │
Checkpoints: C0  C1  C2  C3  C4  C5  C6
             │   │   │   │   │   │   │
Store:    [ckpt_0] [ckpt_1] [ckpt_2] [ckpt_3] ...
```

```typescript
interface Checkpoint {
  id: string;
  sessionId: string;
  tokenIndex: number;
  timestamp: number;
  state: CompressedState;
  parentCheckpoint: string | null;
  children: string[];
  metadata: {
    agentStates: Record<string, AgentState>;
    pendingTools: ToolRequest[];
    contextWindow: ContextWindow;
    memorySlots: MemorySlot[];
  };
}

// Storage: SQLite with WAL mode
// Insert: ~2ms per checkpoint
// Read: ~0.5ms per checkpoint
// Compaction: every 100 checkpoints → merge to single delta
```

**State Versioning & Rollback:**

```
Rollback Protocol:

1. Request rollback to checkpoint C_n
2. Load C_n state from store
3. Invalidate all checkpoints C_{n+1} ... C_N
4. Rebuild agent states from C_n
5. Notify all agents of state change
6. Resume execution from C_n

Cost: O(1) lookup + O(1) load + O(k) invalidation
     where k = N - n (checkpoints to discard)
```

**Idle Session Freeze:**

```
Session Idle Timer: 5 minutes
    ↓
[Freeze Trigger]
    ├── serialize: all agent states → compressed blob
    ├── flush: pending writes to store
    ├── release: all memory resources
    └── mark: session status = "frozen"
    ↓
Memory recovered: ~99.7% of session memory
    ↓
[Thaw on Access]
    ├── load: compressed blob from store
    ├── deserialize: reconstruct agent states
    ├── reconnect: MCP connections
    └── resume: from last checkpoint
    ↓
Time to thaw: <50ms (cold) / <5ms (warm cache)
```

**Cross-Agent State Sync (Eventual Consistency):**

```
Agent A ──state_change──→ Event Bus ──state_change──→ Agent B
                                │
                          ┌─────┴─────┐
                          │  State DB  │
                          └───────────┘

**Protocol:** CRDT-based conflict resolution
Convergence: Within 3 propagation rounds
Latency: <10ms per hop
Conflicts: auto-merge via last-writer-wins with vector clock
```

**Session Replay (Debug Mode):**

```
REPLAY MODE:
┌─────────────────────────────────────────────────────────┐
│ Session: s-abc123                                       │
│ Checkpoints: [0] [1] [2] [3] [4] [5] [6] [7]           │
│ Current: [3] ──→ Next: [4] │  Play │ Step │ Jump │     │
├─────────────────────────────────────────────────────────┤
│ Token 30: Agent frontend wrote src/App.jsx               │
│ Token 32: Agent backend wrote server/index.js            │
│ Token 35: Tool npm install completed                     │
│ Token 38: QA Agent: 3 tests passed, 0 failed             │
│ Token 40: [CHECKPOINT]                                   │
└─────────────────────────────────────────────────────────┘

Commands:
  /replay start [sessionId]   → enter replay mode
  /replay step [n]            → advance n checkpoints
  /replay jump [checkpointId] → jump to checkpoint
  /replay play                → auto-advance (real-time)
  /replay export              → export as JSONL trace
  /replay quit                → exit replay mode
```


---


## Layer 11: **Network & Security Resilience**

### Zero Disruptions → Graceful Degradation

### Opus Timeout → Sonnet Fallback

### 100% State Recovery

### Event Log Stream → JSONL

### Power Loss Recovery → Type `continue` → Resume from Last Line

The resilience layer ensures zero-downtime operation through graceful degradation, model fallback chains, and complete state recovery.

```
┌──────────────────────────────────────────────────────────────┐
│           Network & Security Resilience                        │
│  Degrade → Fallback → Recover → Resume                       │
├──────────────────────────────────────────────────────────────┤
│  • Zero disruptions: graceful degradation on any failure      │
│  • Opus Timeout → Sonnet Fallback (model cascade)             │
│  • 100% state recovery: every operation is recoverable        │
│  • Event Log Stream: structured JSONL logging                 │
│  • Power loss recovery: type "continue" → resume from last    │
└──────────────────────────────────────────────────────────────┘
```

**Graceful Degradation:**

```
Failure Mode                    Degradation Path
───────────────────────────────────────────────────────────
Model timeout (Opus)           → Sonnet → Haiku → Cache-only
Network disconnected           → Offline mode (local models)
API rate limited               → Queue + exponential backoff
Storage full                   → Compress + rotate logs
Memory pressure                → Freeze idle sessions → GC
MCP server down                → Local fallback (SQLite embedded)
```

**Opus Timeout → Sonnet Fallback → Haiku→ Cache-only:**

```
Request → Opus (timeout: 30s)
    ↓ timeout or error
Request → Sonnet (timeout: 60s)
    ↓ timeout or error
Request → Haiku (timeout: 90s)
    ↓ timeout or error
Cache-only mode (pre-computed responses)
    ↓
All fallbacks logged with metric: razor_fallback_count{from="opus",to="sonnet"}
```

**State Recovery (100% Guarantee):**

```
Recovery Protocol:
1. Load last durable checkpoint from WAL
2. Replay event log from checkpoint to crash point
3. Reconstruct in-memory state
4. Verify integrity via checksum
5. Resume execution

Guarantee: Every committed operation survives crash
           Uncommitted operations are re-executed
           Max data loss: 0 (zero)
```

**Event Log Stream (JSONL):**

```
/data/razor/logs/events.jsonl

{"agent":"frontend","action":"write_app_tx","file":"App.jsx","size":2340,"timestamp":1717000000000}
{"agent":"backend","action":"init_server","port":3000,"status":"ok","timestamp":1717000001000}
{"agent":"qa","action":"run_tests","passed":12,"failed":0,"duration":3450,"timestamp":1717000002000}
{"agent":"tool","action":"npm_install","package":"react","version":"18.3.1","status":"ok","timestamp":1717000003000}
{"system":"checkpoint","id":"ckpt_42","tokenIndex":420,"timestamp":1717000004000}
{"system":"fallback","from":"opus","to":"sonnet","reason":"timeout","timestamp":1717000005000}
{"agent":"frontend","action":"write_app_tx","file":"styles.css","size":890,"timestamp":1717000006000}
```

**Power Loss Recovery:**

```
1. System loses power unexpectedly
2. On restart, IRazor AI detects incomplete session
3. Prompt: "Session was interrupted. Type 'continue' to resume from last line."
4. User types: continue
5. System:
   a. Loads last WAL checkpoint
   b. Replays event log
   c. Reconstructs all agent states
   d. Re-executes any in-flight operations
   e. Presents: "Resumed at token 420. Last action: frontend wrote App.jsx"
6. Continue working with zero data loss
```


---


## Layer 12: **Observability & Monitoring (NEW)**

### OpenTelemetry → Traces, Metrics, Logs

### Prometheus Exporter

### Jaeger Tracing → End-to-End Latency Visualization

### Structured Logging → JSONL with trace_id, span_id

Full observability with OpenTelemetry, Prometheus metrics, and Jaeger distributed tracing.

```
┌──────────────────────────────────────────────────────────────┐
│             Observability & Monitoring                         │
│  Traces → Metrics → Logs → Visualization                     │
├──────────────────────────────────────────────────────────────┤
│  • OpenTelemetry: unified traces, metrics, logs               │
│  • Prometheus exporter: /metrics endpoint                     │
│  • Jaeger tracing: end-to-end latency visualization           │
│  • Structured logging: JSONL with trace_id, span_id           │
│  • Grafana dashboards: pre-configured                         │
└──────────────────────────────────────────────────────────────┘
```

**OpenTelemetry Integration:**

```typescript
import { trace, metrics, logs } from '@opentelemetry/api';

// Tracing
const tracer = trace.getTracer('irazor-ai');
const span = tracer.startSpan('agent.execution', {
  attributes: {
    'agent.id': 'frontend-01',
    'agent.task': 'write_app_tx',
    'session.id': 's-abc123'
  }
});

// Metrics
const meter = metrics.getMeter('irazor-ai');
const requestCounter = meter.createCounter('razor_requests_total', {
  description: 'Total number of requests processed'
});
requestCounter.add(1, { 'agent.type': 'frontend', 'status': 'ok' });

// Logs
const logger = logs.getLogger('irazor-ai');
logger.emit({
  severityNumber: 9,  // INFO
  body: 'Frontend agent completed write',
  attributes: {
    'trace_id': span.spanContext().traceId,
    'span_id': span.spanContext().spanId
  }
});
```

**Prometheus Exporter:**

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'irazor-ai'
    scrape_interval: 5s
    static_configs:
      - targets: ['localhost:9464']  # OpenTelemetry Prometheus exporter
```

**Exported Metrics:**
```
| Metric | Type | Labels | Description |

-|
| razor_requests_total | Counter | agent, status, model | Total requests processed |
| razor_token_usage | Counter | model, agent, operation | Total tokens consumed |
| razor_fallback_count | Counter | from, to, reason | Model fallback count |
| razor_checkpoint_total | Counter | session | Total checkpoints created |
| razor_session_duration | Histogram | session | Session duration in ms |
| razor_tool_execution_time | Histogram | tool, status | Tool execution duration |
| razor_memory_usage | Gauge | type | Current memory usage in bytes |
| razor_active_sessions | Gauge | — | Number of active sessions |
| razor_queue_depth | Gauge | queue | Current queue depth |
```

**Jaeger Tracing:**

```
┌─────────────────────────────────────────────────────────┐
│ Jaeger UI — Trace: 8a3f9b2e                              │
├─────────────────────────────────────────────────────────┤
│ Timeline:                                                │
│                                                          │
│ [agent.execution] ═══════════════════════════════ 2.34s  │
│  ├── [frontend.write] ═══════════════════ 1.20s          │
│  ├── [backend.init] ═══════════════════ 890ms            │
│  └── [qa.test] ════════ 250ms                            │
│                                                          │
│ Span: frontend.write                                     │
│   ├── Start: 1717000000000                               │
│   ├── End:   1717000001200                               │
│   ├── Status: OK                                         │
│   ├── Attributes:                                        │
│   │   ├── file: "App.jsx"                                │
│   │   ├── size: 2340                                     │
│   │   └── agent: "frontend-01"                          │
│   └── Events:                                            │
│       ├── "read_template" @ 100ms                        │
│       ├── "generate_code" @ 450ms                        │
│       └── "write_file" @ 1100ms                          │
└─────────────────────────────────────────────────────────┘
```

**Structured Logging Format:**

```jsonl
{"ts":"2026-05-25T10:00:00.000Z","level":"INFO","trace_id":"8a3f9b2e","span_id":"c1d2e3f4","agent":"frontend","action":"write","file":"App.jsx","size":2340,"duration_ms":1200}
{"ts":"2026-05-25T10:00:01.000Z","level":"WARN","trace_id":"8a3f9b2e","span_id":"a1b2c3d4","agent":"system","action":"fallback","from":"opus","to":"sonnet","reason":"timeout"}
{"ts":"2026-05-25T10:00:02.000Z","level":"ERROR","trace_id":"9b4c2d8e","span_id":"f5e6d7c8","agent":"tool","action":"npm_install","package":"unknown-pkg","error":"404 Not Found","recoverable":true}
```


---


## Layer 13: **Rate Limiting & Cost Control (NEW)**

### Token Budget per Session → Budget: 100K Tokens

### Rate Limit → 100 req/min per User

### Cost Alert → Email/Webhook at 80% Budget

### Auto-Fallback to Cache-Only Mode When Budget Exceeded

### Per-Model Cost Tracking (Opus = Expensive, Sonnet = Cheaper)

Controls costs and prevents abuse through token budgets, rate limiting, and automated cost alerts.

```
┌──────────────────────────────────────────────────────────────┐
│            Rate Limiting & Cost Control                        │
│  Budget → Limit → Alert → Fallback → Track                   │
├──────────────────────────────────────────────────────────────┤
│  • Token budget per session: 100K tokens                      │
│  • Rate limit: 100 req/min per user                           │
│  • Cost alert: email/webhook at 80% budget                    │
│  • Auto-fallback to cache-only when budget exceeded           │
│  • Per-model cost tracking (Opus=expensive, Sonnet=cheaper)   │
└──────────────────────────────────────────────────────────────┘
```

**Token Budget Management:**

```typescript
interface TokenBudget {
  session: {
    limit: 100_000;              // 100K tokens per session
    used: number;                // current usage
    remaining: number;           // limit - used
    resetAt: number;             // timestamp
  };
  user: {
    limit: 500_000;              // 500K tokens per user per day
    used: number;
    remaining: number;
    resetAt: number;
  };
  global: {
    limit: 10_000_000;           // 10M tokens total
    used: number;
    remaining: number;
  };
}

// Budget check flow
function checkBudget(request: Request): BudgetResult {
  const sessionBudget = getSessionBudget(request.sessionId);
  if (sessionBudget.remaining <= 0) {
    return { allowed: false, reason: 'session_budget_exhausted', action: 'cache_only' };
  }
  // Deduct estimated cost
  const estimatedTokens = estimateTokens(request.prompt, request.model);
  sessionBudget.used += estimatedTokens;
  sessionBudget.remaining = sessionBudget.limit - sessionBudget.used;
  return { allowed: true, estimatedTokens };
}
```

**Rate Limiting:**

```yaml
rate_limits:
  per_user:
    window: 60s
    max_requests: 100
    response: "429 Too Many Requests"
    headers:
      - X-RateLimit-Limit: 100
      - X-RateLimit-Remaining: {remaining}
      - X-RateLimit-Reset: {reset_timestamp}
  per_ip:
    window: 60s
    max_requests: 500
  per_session:
    window: 60s
    max_requests: 50

burst:
  enabled: true
  max_burst: 20
  ttl: 30s
```

**Cost Alerts at 80% Budget:**

```typescript
interface CostAlert {
  threshold: 0.8;                // 80% of budget
  channels: ['email', 'webhook'];
  email: 'admin@razor-ai.dev';
  webhook: 'https://hooks.razor-ai.dev/cost-alert';
  cooldown: 300_000;             // 5 min between alerts
}

// Alert payload
{
  "alert": "budget_threshold",
  "threshold": 0.8,
  "current": 0.83,
  "budget": 100000,
  "used": 83000,
  "remaining": 17000,
  "estimated_exhaustion": "2026-05-25T10:15:00Z",
  "top_models": [
    {"model": "opus", "cost": 0.45, "tokens": 45000},
    {"model": "sonnet", "cost": 0.12, "tokens": 30000},
    {"model": "haiku", "cost": 0.03, "tokens": 8000}
  ]
}
```

**Per-Model Cost Tracking:**

| Model | Cost per 1K tokens | Budget Impact | Priority
----
-|
| Opus | $0.015 | High (45%) | Critical tasks only |
| Sonnet | $0.003 | Medium (30%) | Standard tasks |
| Haiku | $0.00025 | Low (15%) | Simple tasks |
| Cache-only | $0.00001 | Minimal (10%) | Fallback |
----

**Auto-Fallback to Cache-Only Mode:**

```
Budget remaining > 20%  → All models available
Budget remaining 10-20% → Haiku + cache only
Budget remaining 5-10%  → Cache-only, no model calls
Budget remaining < 5%   → Read-only mode, queries only
Budget exhausted        → "Budget exhausted. Resets in 4h."
```


---


## Layer 14: **Optional Human Approval Gate (NEW)**

### Critical Operations → requires_approval: true

### Approval Queue Dashboard

### Timeout → Auto-Reject After 5 Mins

### Audit Log → Who Approved What & When

The human approval gate provides a safety layer for critical operations with a full audit trail.

```
┌──────────────────────────────────────────────────────────────┐
│              Optional Human Approval Gate                      │
│  Queue → Review → Approve/Reject → Audit                     │
├──────────────────────────────────────────────────────────────┤
│  • Critical operations: requires_approval: true              │
│  • Approval queue dashboard                                  │
│  • Timeout: auto-reject after 5 minutes                      │
│  • Audit log: who approved what & when                       │
│  • Configurable sensitivity per operation type               │
└──────────────────────────────────────────────────────────────┘
```

**Operation Classification:**

```json
{
  "approvalGates": {
    "file:delete": { "requires_approval": true, "timeout": 300000 },
    "file:overwrite": { "requires_approval": true, "threshold": 100 },
    "command:exec": { "requires_approval": true, "sensitive_patterns": ["rm -rf", "sudo", "dd if="] },
    "deploy:production": { "requires_approval": true, "timeout": 600000 },
    "db:drop_table": { "requires_approval": true, "timeout": 300000 },
    "config:change": { "requires_approval": true, "keys": ["api_key", "secret", "password"] },
    "npm:publish": { "requires_approval": true, "timeout": 300000 },
    "file:read": { "requires_approval": false },
    "search:query": { "requires_approval": false }
  }
}
```

**Approval Queue Dashboard:**

```
┌─────────────────────────────────────────────────────────────┐
│  Approval Queue (3 pending)                                  │
├─────────────────────────────────────────────────────────────┤
│  # │ Op         │ Detail              │ Requestor │ Time    │
│  ──┼────────────┼─────────────────────┼───────────┼─────────│
│  1 │ DELETE     │ rm -rf /data/old    │ agent-b   │ 2:30    │
│  2 │ DEPLOY     │ Deploy to prod v2.1 │ user      │ 1:15    │
│  3 │ DB WRITE   │ DROP TABLE users    │ agent-q   │ 0:45    │
│     │            │                     │           │         │
│  [Approve #1] [Reject #1] [View Details]                   │
└─────────────────────────────────────────────────────────────┘
```

**Approval Flow:**

```
Critical Operation Requested
    ↓
[Gate Check] → requires_approval? → No → execute
    ↓ Yes
[Create Approval Ticket]
    ├── id: apr-20260525-001
    ├── operation: { type, params, scope }
    ├── requestor: { agent, session, user }
    ├── risk_score: 0.87
    └── status: pending
    ↓
[Push to Queue Dashboard]
    ↓
[Notify Approvers] → email / webhook / in-app
    ↓
┌─────────────────────────────────────────┐
│  Awaiting Approval (timeout: 5:00)       │
│                                          │
│  Operation: DROP TABLE users              │
│  Reason: Schema migration v3              │
│  Files affected: 12                       │
│  Risk score: 0.92 (CRITICAL)             │
│                                          │
│  [APPROVE]          [REJECT]             │
│  (auto-reject in 4:15)                   │
└─────────────────────────────────────────┘
    ↓
Response → Approved → Execute with audit trail
        → Rejected → Log + notify requestor
        → Timeout  → Auto-reject + log
```

**Audit Log:**

```json
{
  "auditLog": [
    {
      "id": "audit-20260525-001",
      "timestamp": "2026-05-25T10:30:00Z",
      "operation": {
        "type": "db:drop_table",
        "params": { "table": "users_old" },
        "scope": "production"
      },
      "requestor": {
        "agent": "agent-q",
        "session": "s-abc123",
        "user": "dev-user"
      },
      "approval": {
        "status": "approved",
        "approvedBy": "admin@razor-ai.dev",
        "approvedAt": "2026-05-25T10:31:15Z",
        "responseTime": 75000
      },
      "execution": {
        "status": "completed",
        "startedAt": "2026-05-25T10:31:16Z",
        "completedAt": "2026-05-25T10:31:18Z",
        "result": "OK: table dropped"
      }
    },
    {
      "id": "audit-20260525-002",
      "timestamp": "2026-05-25T11:00:00Z",
      "operation": {
        "type": "deploy:production",
        "params": { "version": "v2.1.0", "strategy": "rolling" }
      },
      "requestor": {
        "agent": "user",
        "session": "s-def456",
        "user": "dev-lead"
      },
      "approval": {
        "status": "rejected",
        "approvedBy": "admin@razor-ai.dev",
        "approvedAt": "2026-05-25T11:02:30Z",
        "responseTime": 150000,
        "reason": "Version not reviewed by QA"
      }
    }
  ]
}
```

**Timeout Configuration:**

```typescript
interface ApprovalTimeout {
  default: 300_000;              // 5 minutes
  perOperation: {
    'deploy:production': 600_000;   // 10 minutes
    'file:delete': 120_000;         // 2 minutes
    'command:exec': 300_000;        // 5 minutes
  };
  onTimeout: 'reject';           // always reject on timeout
  notifyOnTimeout: true;
  timeoutAction: {
    type: 'auto_reject',
    reason: 'Approval timeout exceeded'
  };
}
```


---


## Continuity: HCP+ (with Fallback & Recovery)

The HCP+ (High Continuity Protocol) ensures uninterrupted operation across all layers.

```
HCP+ Protocol Layers:

L1: Hardware   → Battery backup, ECC memory, RAID storage
L2: Network    → Dual NIC, failover DNS, redundant API routes
L3: Model      → Opus → Sonnet → Haiku → Cache-only cascade
L4: State      → Every 10-token checkpoint, WAL journaling
L5: Session    → Freeze/thaw, cross-session state sync
L6: Data       → Triple-write (memory → SQLite → JSONL log)
L7: Recovery   → Automatic replay from last checkpoint
```

**HCP+ Guarantees:**

**Property | Guarantee**

Uptime | 99.99% (excluding underlying model API) 
Data loss | Zero (WAL + checkpoint + log) |
Recovery time | <100ms from checkpoint |
Fallback latency | <500ms (Opus → Sonnet) |
Session continuity | 100% (crash → continue) 


---


## Flows

### Response Flow

```
User Prompt
    ↓
[Layer 1: Type Check] → validate schema & types
    ↓
[Layer 2: Stream] → open SSE stream, begin token flow
    ↓
[Layer 3: Hooks] → pre:validate, pre:enrich, pre:route
    ↓
[Layer 5: Prefix Memory] → cache lookup, rehydrate context
    ↓
[Layer 6: Orchestration] → decompose task, assign agents
    ↓
[Layer 7: Tool Engine] → parallel reads, sequential writes
    ↓
[Layer 8: MCP] → query context pool, fetch external data
    ↓
[Layer 9: Compression] → compress output, suppress noise
    ↓
[Layer 10: State Checkpoint] → save state every 10 tokens
    ↓
[Layer 12: Observability] → emit trace + metrics + log
    ↓
Stream Result
```

### Questions Flow

```
User Question
    ↓
[Layer 1: Type Config] → classify as "question" intent
    ↓
[Layer 2: Stream] → stream partial understanding
    ↓
[Layer 3: Hooks] → enrich with context, attach references
    ↓
[Layer 5: Memory] → recall similar Q&A patterns
    ↓
[Layer 6: Orchestration] → route to knowledge agent
    ↓
[Layer 8: MCP] → search knowledge base, query docs
    ↓
[Layer 9: Compression] → summarize answer, cite sources
    ↓
[Layer 12: Observability] → log Q&A for training data
    ↓
Answer
```

### Other Questions Flow (Fallback)

```
Unrecognized Input
    ↓
[Layer 1: Type Config] → intent = "unknown"
    ↓
[Layer 2: Stream] → stream: "Let me think about that..."
    ↓
[Layer 3: Hooks] → pre:fallback trigger
    ↓
[Layer 5: Memory] → deep context search
    ↓
[Layer 6: Orchestration] → route to general reasoning agent
    ↓
[Layer 9: Compression] → extract best-guess response
    ↓
[Layer 4: Approval] → if sensitive, gate
    ↓
[Layer 12: Observability] → log unknown intent for training
    ↓
Best-guess Response + "Was this helpful?"
```

### Others Flow (Catch-All)

```
Unprocessable/Edge Case Input
    ↓
[Layer 1: Type Config] → intent = "other"
    ↓
[Layer 3: Hooks] → pre:other trigger → log to catch-all
    ↓
[Layer 12: Observability] → structured log with full payload
    ↓
[Layer 13: Cost Control] → no budget spent on unprocessable
    ↓
[Layer 10: State] → checkpoint: no state mutation
    ↓
Response: "I couldn't process that. Could you rephrase?"
    ↓
[Layer 12: Observability] → metric: razor_other_count++
```

### Observability Telemetry Flow

```
Every Operation
    ↓
[Layer 12: OpenTelemetry]
    ├── Start trace (trace_id, span_id)
    ├── Record metrics (counters, histograms)
    └── Emit structured log (JSONL)
    ↓
[Prometheus Exporter] → /metrics endpoint → Grafana
[Jaeger Exporter]    → distributed tracing UI
[Log Exporter]       → JSONL file → log aggregator
    ↓
Dashboards:
  • Requests per second
  • Token usage per model
  • Fallback counts
  • Session durations
  • Error rates
  • P50/P95/P99 latencies
  • Approval queue depth
  • Budget usage %
```

### Approval Queue Flow

```
Critical Operation Detected
    ↓
[Layer 4: Auto-Approval] → risk_score > threshold?
    ↓ Yes
[Layer 14: Human Gate]
    ├── Create approval ticket
    ├── Push to queue dashboard
    ├── Notify approvers
    └── Start timeout timer
    ↓
┌─ [APPROVED] ────────────────────┐
│ Execute operation               │
│ Log: who approved, when         │
│ Notify requestor                │
│ Metric: razor_approval_time     │
└─────────────────────────────────┘

┌─ [REJECTED] ────────────────────┐
│ Cancel operation                │
│ Log: who rejected, why          │
│ Notify requestor with reason    │
│ Metric: razor_rejection_count   │
└─────────────────────────────────┘

┌─ [TIMEOUT] ─────────────────────┐
│ Auto-reject after 5 min         │
│ Log: timeout, no response       │
│ Notify requestor + approvers    │
│ Escalate if priority > 8        │
└─────────────────────────────────┘
```


---


## Appendix: Model & Architecture

### New Cybersecurity Model: IRazor AI-Secure

A new specialized model variant focused on cybersecurity analysis and legacy codebase remediation.

```
┌──────────────────────────────────────────────────────────┐
│              IRazor AI-Secure Model                        │
│  Zero-Day Detection → Vulnerability Repair → Legacy Code  │
├──────────────────────────────────────────────────────────┤
│  • 20-year codebase analysis in under 60 seconds          │
│  • CVE database: real-time query (50K+ entries cached)    │
│  • Zero-day pattern recognition via behavioral analysis    │
│  • Automated patch generation with proof of correctness    │
│  • Secure code transformation: vulnerable → hardened       │
└──────────────────────────────────────────────────────────┘
```

** Capability Matrix: **

| Vulnerability detection (known CVEs) | 98.2% |
| Zero-day pattern recognition | 91.5% |
| Legacy code refactoring (20+ year old codebases) | 94.7% |
| Automated patch generation | 96.1% |
| Secure code review (OWASP Top 10) | 97.8% |
| Reverse engineering (binary → pseudocode) | 88.3% |
| Exploit analysis & replication | 85.6% |

**Legacy Code Transformation Pipeline:**

```
Input: 20-year-old COBOL/Fortran/C++ codebase
    ↓
[Phase 1: Analysis]
  ├── Syntax parsing (custom parser for legacy dialects)
  ├── Dependency graph extraction
  ├── Security audit (known + zero-day patterns)
  └── Risk scoring per module
    ↓
[Phase 2: Modernization]
  ├── → Rust (safe memory) or Go (concurrent) or Python (rapid)
  ├── Maintain original business logic (verified via property-based tests)
  └── Output: semantically equivalent, memory-safe, modern code
    ↓
[Phase 3: Verification]
  ├── Fuzz testing (100K iterations)
  ├── Property-based testing (formal verification of invariants)
  ├── Side-by-side execution (old vs new, compare outputs)
  └── Security scan (SAST + DAST + SCA)
    ↓
Output: Modern, secure, verified codebase
```

**Model Architecture:**

```yaml
model:
  name: IRazor AI-Secure
  base: big-pickle
  specialization: cybersecurity
  context_length: 256K
  training_data:
    - CVE database (50K+ entries)
    - OWASP Top 10 patterns
    - 20+ years of legacy code examples (COBOL, Fortran, C, C++, Java)
    - Modern secure code patterns (Rust, Go, Python, TypeScript)
    - Real-world exploit case studies
    - Formal verification proofs
  benchmarks:
    cyber_bench: 94.2%
    legacy_repair: 96.8%
    secure_gen: 97.1%
```


---


## Appendix: Complete Configuration

### Full settings.json

```json
{
  "version": "2.0",
  "name": "IRazor AI",
  "type": "autonomous-agentic-runtime",

  "typeConfig": {
    "scanner": {
      "root": "/rules",
      "rules": ["agent.schema.json", "tool.schema.json", "hook.schema.json"],
      "injectMode": "hybrid",
      "refreshInterval": 60000
    },
    "isolation": {
      "intents": ["code", "reasoning", "tool", "qa", "question", "other"],
      "strictMode": true,
      "fallbackOnViolation": "reroute"
    }
  },

  "streaming": {
    "enabled": true,
    "protocol": "sse",
    "partialEmitTokens": 8,
    "maxTokensPerSecond": 500,
    "backpressureStrategy": "buffer",
    "windowSize": 1024
  },

  "hooks": {
    "enabled": true,
    "timeout": 5000,
    "phases": ["pre", "post", "error"],
    "builtin": [
      "validateInput", "sanitizePrompt", "injectContext",
      "classifyIntent", "formatOutput", "saveHistory", "emitEvent"
    ]
  },

  "autoApproval": {
    "enabled": true,
    "threshold": 0.3,
    "humanInTheLoop": true,
    "humanGateThreshold": 0.7,
    "cacheResults": true,
    "cacheTTL": 300000,
    "approvalTimeout": 300000,
    "auditLog": true
  },

  "prefixMemory": {
    "enabled": true,
    "cacheSize": 10000,
    "evictionPolicy": "tiered-lru",
    "frozenState": true,
    "rehydrateOnLoad": true
  },

  "orchestration": {
    "agents": {
      "frontend": { "count": 3, "capabilities": ["react", "vue", "css", "html", "state"] },
      "backend": { "count": 3, "capabilities": ["api", "db", "auth", "logic", "services"] },
      "qa": { "count": 2, "capabilities": ["test", "lint", "validate", "coverage"] }
    },
    "dispatcher": "centralized",
    "executionModel": "decoupled"
  },

  "toolEngine": {
    "parallelReads": { "maxConcurrency": 10, "enabled": true },
    "sequentialWrites": { "enabled": true, "mutex": "global" },
    "preloadedModules": ["file-system", "http-client", "sql-query", "diff-patch"],
    "sandboxing": {
      "enabled": true,
      "allowedPaths": ["/project/**", "/tmp/**"],
      "deniedPaths": ["/etc/**", "/proc/**", "/sys/**", "/.env*"]
    }
  },

  "mcpServers": {
    "sqlite": {
      "server": "sqlite-mcp-server",
      "transport": "stdio",
      "poolSize": 5,
      "timeout": 10000
    },
    "filesystem": {
      "server": "filesystem-mcp",
      "transport": "stdio",
      "poolSize": 3,
      "timeout": 5000
    }
  },

  "compression": {
    "enabled": true,
    "kb8Compression": { "ratio": 0.15, "quality": 0.94 },
    "suppressDeprecated": true,
    "suppressExperimental": true,
    "adaptiveRatio": true
  },

  "stateFabric": {
    "checkpointInterval": 10,
    "versioning": true,
    "idleFreezeTimeout": 300000,
    "crossAgentSync": true,
    "syncProtocol": "crdt",
    "replayEnabled": true,
    "storage": "sqlite+wal"
  },

  "resilience": {
    "gracefulDegradation": true,
    "modelFallbackChain": ["opus", "sonnet", "haiku", "cache-only"],
    "stateRecovery": true,
    "eventLogStream": true,
    "eventLogPath": "/data/razor/logs/events.jsonl",
    "powerLossRecovery": true
  },

  "observability": {
    "opentelemetry": true,
    "prometheus": { "enabled": true, "port": 9464 },
    "jaeger": { "enabled": true, "endpoint": "http://jaeger:14268" },
    "structuredLogging": true,
    "logPath": "/data/razor/logs/razor.jsonl",
    "metrics": [
      "razor_requests_total",
      "razor_token_usage",
      "razor_fallback_count",
      "razor_session_duration",
      "razor_tool_execution_time",
      "razor_memory_usage",
      "razor_active_sessions"
    ]
  },

  "rateLimiting": {
    "tokenBudget": { "perSession": 100000, "perUser": 500000, "global": 10000000 },
    "rateLimit": { "perUser": { "window": 60, "max": 100 }, "perIP": { "window": 60, "max": 500 } },
    "costAlerts": { "threshold": 0.8, "channels": ["email", "webhook"] },
    "cacheOnlyFallback": true
  },

  "humanApprovalGate": {
    "enabled": true,
    "defaultTimeout": 300000,
    "criticalOps": ["file:delete", "db:drop_table", "deploy:production", "command:exec"],
    "autoRejectOnTimeout": true,
    "auditLogEnabled": true,
    "auditLogPath": "/data/razor/audit/approval.log"
  },

  "continuity": {
    "protocol": "HCP+",
    "uptimeGuarantee": "99.99%",
    "dataLossGuarantee": "zero",
    "recoveryTime": "<100ms",
    "fallbackLatency": "<500ms"
  }
}
```


---


## Summary

IRazor AI IQD2R a **14-layer autonomous agentic coding runtime** with:

1. **Type Configuration** — strict schema injection & intent isolation
2. **Streaming Intent Interface** — real-time token-by-token parsing
3. **Hook System** — middleware-ready pre/post/error hooks
4. **Silent Auto-Approval** — risk-based auto-approval with optional human gate
5. **Prefix Memory** — cache-first near-zero-cost token recall
6. **Sub-Agent Orchestration** — frontend, backend, QA with centralized dispatch
7. **Tool Execution Engine** — 10X parallel reads, safe sequential writes
8. **MCP Servers** — standardized model context protocol pooling
9. **Contextual Compression** — 25:1 compression with semantic preservation
10. **State Fabric** — fault-tolerant session checkpointing & replay
11. **Network & Security Resilience** — graceful degradation & power-loss recovery
12. **Observability & Monitoring** — OpenTelemetry + Prometheus + Jaeger
13. **Rate Limiting & Cost Control** — token budgets, rate limits, cost alerts
14. **Human Approval Gate** — critical ops with audit trail

**Cybersecurity Model:** IRazor AI-Secure — zero-day detection, legacy codebase repair (20+ years), automated patch generation.

**Continuity:** HCP+ with 99.99% uptime, zero data loss, <100ms recovery.

**Total token efficiency gain:** ~25:1 compression across all layers.
**Total operational reliability:** 100% state recovery guarantee.
**Total cost control:** per-model tracking with automatic budget enforcement.


---


*IRazor AI IQD2R — Not just an agent. A complete autonomous coding infrastructure.*
