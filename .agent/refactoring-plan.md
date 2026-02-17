# Refactoring Plan – Phase 2

## Overview
This plan addresses every point raised. Changes are grouped by module and ordered by dependency.

---

## ✅ 1. ControlEvents → Split into Core + UI Events

**Completed.** Split `ControlEvents` into:
- `core/.../event/ControlEvents.java` — `LogEvent`, `TriggerAgentAnalysisEvent` (agent ↔ UI bridge)
- `terminal/.../ui/event/UiEvents.java` — `SearchEvent`, `SearchNextEvent`, `ToggleGraphViewEvent`, `ClearTerminalEvent`, `TerminalBlinkEvent`

**Impact**: Imports updated in WsbgTerminalApp, DashboardController, GraphController.

---

## ✅ 2. Agent Prompts → Resource Files

**Completed.** Extracted to `/agent/src/main/resources/prompts/`:
- `thread-analysis.txt`, `observation-report.txt`, `headline-generation.txt`, `vision.txt`

**Loader**: `PromptLoader` utility class with `{{PLACEHOLDER}}` substitution and caching.

---

## ✅ 3. PassiveMonitorService Refactoring

**Completed.** Decomposed into:
- `InvestigationCluster` — state container (top-level class)
- `SignificanceScorer` — scoring logic + keyword detection
- `ReportBuilder` — context assembly, headline prompting, vision integration
- `PassiveMonitorService` — orchestration only (~290 lines)

---

## ✅ 4. ChatService Cleanup

**Completed.** Prompts extracted via `PromptLoader`. `Map` import added.

---

## ✅ 5. SQL → Resource Files (SqlDatabaseService)

**Completed.** 17 `.sql` files in `/database/src/main/resources/sql/`.
`SqlLoader` utility class. All 20+ inline SQL strings replaced.

---

## ✅ 6. Launcher FQN Cleanup

**No change needed.** Runtime string constant for reflection, not a code FQN.

---

## ✅ 7. AppModule FQN Cleanup

**Already clean.** Verified in previous session.

---

## ✅ 8. DashboardController — Extract HTML/CSS/JS to Resource Files

**Completed.** Created `/terminal/src/main/resources/webview/`:
- `terminal.html`, `terminal.css`, `terminal.js`
- `WebViewLoader` utility class for assembly + font injection

`DashboardController.initialize()` dropped from ~300 lines to ~28 lines.

---

## ✅ 9. WsbgTerminalApp — Decompose Titlebar

**Completed.** Extracted to `TitleBarFactory`:
- `createSearchBox()`, `createTrafficLights()`, `createUtilityControls()`, `createBroomContainer()`
- `setupDrag()`, overlay injection logic

`WsbgTerminalApp` dropped from 570 lines to ~200 lines.

---

## ✅ 10. Graph Classes — Split Long Files

**Completed.** All FQNs cleaned and classes decomposed:

**GraphController (773 → 536 lines)**:
- Extracted `LayoutNode` + tree layout → `GraphLayoutEngine` (193 lines)
  - `buildLayoutTree()`, `calculateSubtreeSizes()`, `calculateMaxDepth()`, `applyBranchLayout()`, `getCommentColor()`
  - `NodeResolver` functional interface for simulation lookup
- Cleaned `processQueues()`: removed dead no-op `removeIf`, redundant first reap pass, verbose thought-log comments
- Removed unused `removeNodeAndEdges()` method
- Fixed remaining FQNs: `java.awt.Desktop`, `java.net.URI`, `CompletableFuture`, `Iterator`
- Replaced `Pair<>` pattern with `Object[]` (removed Pair import)

**GraphView (916 → 728 lines)**:
- Extracted magnifier + ambient glow → `MagnifierRenderer` (230 lines)
  - Stateless rendering via `CameraState` record + functional interfaces
  - `drawIceMagnifier()`, `drawAmbientGlow()`
- Cleaned unused imports: `LinearGradient`, `ArcType`

**Other FQN cleanup (all files)**:
- `GraphSidebar` — `Timeline`, `KeyFrame`, `KeyValue`, `Interpolator`, `Platform`, `Node`
- `LiquidGlass` — `BlendMode`, `Map`, `IdentityHashMap`
- `WsbgTerminalApp` — `@Subscribe` annotation, `FadeTransition`

**New files**: `GraphLayoutEngine.java`, `MagnifierRenderer.java`

---

## Phase 2 Summary

| # | Task | Status | Lines Saved |
|---|------|--------|-------------|
| 1 | ControlEvents split | ✅ | ~30 |
| 2 | Prompt extraction | ✅ | ~40 |
| 3 | PassiveMonitorService decomp | ✅ | ~270 |
| 4 | ChatService cleanup | ✅ | ~20 |
| 5 | SQL extraction | ✅ | ~80 |
| 6 | Launcher FQN | ✅ (no-op) | 0 |
| 7 | AppModule FQN | ✅ (no-op) | 0 |
| 8 | DashboardController HTML/CSS/JS | ✅ | ~270 |
| 9 | WsbgTerminalApp titlebar | ✅ | ~370 |
| 10 | Graph classes + FQN cleanup | ✅ | ~425 |

**Total lines eliminated from long methods: ~1,505**
**New focused files created: ~14**

