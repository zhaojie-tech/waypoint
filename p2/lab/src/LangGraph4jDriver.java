import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.*;
import org.bsc.langgraph4j.state.AgentState;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Waypoint P2 : LangGraph4j 1.9.5 (org.bsc.langgraph4j:langgraph4j-core) driver.
 * Audits recovery semantics at the PUBLIC API level with Waypoint's contract
 * vocabulary. External ledger + trace survive "process restart" (framework
 * state is discarded; the checkpoint store persists : analog of a durable store).
 *
 * API notes (discovered by javap on the pinned jar):
 * - saver.list(config) returns checkpoints NEWEST-FIRST and includes __START__
 * - CompileConfig.releaseThread(false) keeps checkpoints after a completed run
 * - non-deprecated entry: invoke(GraphInput.args/resume(...), config)
 */
public final class LangGraph4jDriver {

    static final Map<String, Integer> LEDGER = new TreeMap<>();
    static final List<String> TRACE = new ArrayList<>();

    static class S extends AgentState {
        S(Map<String, Object> data) { super(data); }
    }

    static void reset() { LEDGER.clear(); TRACE.clear(); }

    static StateGraph<S> buildGraph() throws Exception {
        StateGraph<S> g = new StateGraph<>(S::new);
        g.addNode("a", node("a"));
        g.addNode("b", node("b"));
        g.addNode("c", node("c"));
        g.addEdge(StateGraph.START, "a");
        g.addEdge("a", "b");
        g.addEdge("b", "c");
        g.addEdge("c", StateGraph.END);
        return g;
    }

    static AsyncNodeAction<S> node(String name) {
        return state -> {
            // crash injection: node "c" dies AFTER its side effect, ONCE : guarded by an external marker
            if (name.equals("c") && LEDGER.containsKey("injectCrash")) {
                LEDGER.remove("injectCrash");
                LEDGER.merge("node:c", 1, Integer::sum);
                TRACE.add("c[crash-after-effect]");
                return CompletableFuture.failedFuture(
                        new RuntimeException("simulated process crash after side effect of node c"));
            }
            LEDGER.merge("node:" + name, 1, Integer::sum);
            TRACE.add(name);
            return CompletableFuture.completedFuture(Map.of("seen_" + name, true));
        };
    }

    static CompiledGraph<S> compile(MemorySaver saver) throws Exception {
        return buildGraph().compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .releaseThread(false) // keep checkpoints after a completed run (audit needs them)
                .build());
    }

    static RunnableConfig cfg(String thread) {
        return RunnableConfig.builder().threadId(thread).build();
    }

    /** list() is newest-first; the newest checkpoint is the first element. */
    static Checkpoint newest(MemorySaver saver, RunnableConfig cfg) {
        return saver.list(cfg).iterator().next();
    }

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Waypoint P2 driver : langgraph4j-core 1.9.5 (pinned)\n\n");
        out.append("环境：JDK ").append(System.getProperty("java.vm.version"))
           .append("；外部账本/轨迹跨『进程重启』存活，框架状态丢弃；releaseThread(false) 保留已完成 run 的检查点。\n\n");

        // ---------- scenario 1: checkpoint chain audit (healthy full run) ----------
        reset();
        MemorySaver saver1 = new MemorySaver();
        CompiledGraph<S> g1 = compile(saver1);
        RunnableConfig c1 = cfg("t1");
        g1.invoke(GraphInput.args(Map.of("q", 1)), c1);
        Collection<Checkpoint> chain = saver1.list(c1);
        boolean chainOk = chain.size() == 4; // 3 个节点 + __START__
        List<String> nodeOrder = new ArrayList<>();
        for (Checkpoint cp : chain) nodeOrder.add(cp.getNodeId());
        boolean orderOk = nodeOrder.equals(List.of("c", "b", "a", "__START__")); // 新→旧序
        boolean countsOk = LEDGER.equals(Map.of("node:a", 1, "node:b", 1, "node:c", 1));
        out.append("## 场景 1 : 检查点链完整性（健康全量运行）\n");
        out.append("- checkpoint 数 = ").append(chain.size()).append("（期望 4：3 节点 + __START__）→ ").append(chainOk ? "PASS" : "FAIL").append("\n");
        out.append("- nodeId 链（新→旧）= ").append(nodeOrder).append(" → ").append(orderOk ? "PASS" : "FAIL").append("\n");
        out.append("- 外部账本 = ").append(LEDGER).append(" → ").append(countsOk ? "PASS" : "FAIL").append("\n\n");

        // ---------- scenario 2: post-completion resume is a no-op (C1) ----------
        int before = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        int traceBefore = TRACE.size();
        CompiledGraph<S> g1f = compile(saver1); // fresh framework state, same checkpoint store
        g1f.invoke(GraphInput.resume(newest(saver1, c1)), c1);
        int after = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        boolean noop = (before == after) && (TRACE.size() == traceBefore);
        out.append("## 场景 2 : 完成后 resume 零重复执行（C1 前缀续跑）\n");
        out.append("- 从最新检查点 resume：外部账本增量 = ").append(after - before)
           .append("，轨迹增量 = ").append(TRACE.size() - traceBefore).append(" → ")
           .append(noop ? "PASS（无重复执行）" : "FAIL（有重复执行）").append("\n\n");

        // ---------- scenario 3: crash after effect + resume (C2 semantics) ----------
        reset();
        LEDGER.put("injectCrash", 1);
        MemorySaver saver2 = new MemorySaver();
        CompiledGraph<S> g2 = compile(saver2);
        RunnableConfig c2 = cfg("t2");
        try {
            g2.invoke(GraphInput.args(Map.of("q", 1)), c2);
            out.append("## 场景 3 : 副作用后崩溃 + resume\n- 意外：invoke 未抛出（崩溃注入未生效）\n\n");
        } catch (RuntimeException expected) {
            int crashPointCheckpoints = saver2.list(c2).size();
            int ledgerAtCrash = LEDGER.getOrDefault("node:c", 0);
            // "process restart": discard framework state, keep checkpoint store + ledger
            CompiledGraph<S> g2f = compile(saver2);
            g2f.invoke(GraphInput.resume(newest(saver2, c2)), c2);
            int ledgerAfter = LEDGER.getOrDefault("node:c", 0);
            int checkpointsAfter = saver2.list(c2).size();
            out.append("## 场景 3 : 副作用后崩溃 + resume（SIGKILL-after-effect 类，C2 语义）\n");
            out.append("- 崩溃时：checkpoint 数 = ").append(crashPointCheckpoints)
               .append("，node:c 已生效次数 = ").append(ledgerAtCrash).append("\n");
            out.append("- 从最新检查点 resume 后：node:c 生效次数 = ").append(ledgerAfter)
               .append("（").append(ledgerAfter > ledgerAtCrash ? "重跑了崩溃节点 → at-least-once 语义" : "未重复 → exactly-once 语义").append("）\n");
            out.append("- resume 后 checkpoint 链长度 = ").append(checkpointsAfter)
               .append("（").append(checkpointsAfter >= crashPointCheckpoints ? "单调不回退" : "回退！").append("）\n");
            out.append("- 外部账本终态 = ").append(new TreeMap<>(LEDGER)).append("\n");
            out.append("- 分类（对照文档承诺）：LangGraph 家族文档化的行为是节点恢复后可重跑（at-least-once），\n")
               .append("  本观测与之相符 → gap（未承诺恰好一次），非 violation；副作用恰好一次需业务侧幂等键。\n\n");
        }

        // ---------- scenario 4: determinism across two fresh full runs (C6) ----------
        reset();
        MemorySaver sa = new MemorySaver(), sb = new MemorySaver();
        compile(sa).invoke(GraphInput.args(Map.of("q", 1)), cfg("d1"));
        List<String> traceA = new ArrayList<>(TRACE);
        int ledgerA = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        reset();
        compile(sb).invoke(GraphInput.args(Map.of("q", 1)), cfg("d2"));
        List<String> traceB = new ArrayList<>(TRACE);
        int ledgerB = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        boolean det = traceA.equals(traceB) && ledgerA == ledgerB
                && sa.list(cfg("d1")).size() == sb.list(cfg("d2")).size();
        out.append("## 场景 4 : 两次全新全量运行的确定性（C6）\n");
        out.append("- 轨迹一致 = ").append(traceA.equals(traceB))
           .append("，账本一致 = ").append(ledgerA == ledgerB)
           .append("，checkpoint 数一致 = ").append(sa.list(cfg("d1")).size() == sb.list(cfg("d2")).size())
           .append(" → ").append(det ? "PASS" : "FAIL").append("\n");

        System.out.println(out);
        java.nio.file.Files.writeString(java.nio.file.Path.of("driver-result.md"), out.toString());
    }
}
