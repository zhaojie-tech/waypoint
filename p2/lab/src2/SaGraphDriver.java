import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Waypoint P2 : spring-ai-alibaba-graph-core 1.1.2.4-security-fix driver.
 *  Same audit skeleton as the langgraph4j driver: external ledger + trace
 *  survive "process restart"; framework state is discarded. */
public final class SaGraphDriver {

    static final Map<String, Integer> LEDGER = new TreeMap<>();
    static final List<String> TRACE = new ArrayList<>();

    static AsyncNodeAction node(String name) {
        return state -> {
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

    static StateGraph buildGraph() throws Exception {
        StateGraph g = new StateGraph(() -> Map.of("seen", new ReplaceStrategy()));
        g.addNode("a", node("a"));
        g.addNode("b", node("b"));
        g.addNode("c", node("c"));
        g.addEdge(StateGraph.START, "a");
        g.addEdge("a", "b");
        g.addEdge("b", "c");
        g.addEdge("c", StateGraph.END);
        return g;
    }

    static CompiledGraph compile(MemorySaver saver) throws Exception {
        SaverConfig sc = SaverConfig.builder().register(saver).build();
        return buildGraph().compile(CompileConfig.builder().saverConfig(sc).build());
    }

    static RunnableConfig cfg(String thread) {
        return RunnableConfig.builder().threadId(thread).build();
    }

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# Waypoint P2 driver : spring-ai-alibaba-graph-core 1.1.2.4-security-fix (pinned)\n\n");
        out.append("环境：JDK ").append(System.getProperty("java.vm.version"))
           .append("；外部账本/轨迹跨『进程重启』存活，框架状态丢弃。\n\n");

        // scenario 1: checkpoint chain audit
        reset();
        MemorySaver saver1 = new MemorySaver();
        CompiledGraph g1 = compile(saver1);
        RunnableConfig c1 = cfg("t1");
        g1.invoke(Map.of("q", 1), c1);
        Collection<Checkpoint> chain = saver1.list(c1);
        List<String> nodeOrder = new ArrayList<>();
        for (Checkpoint cp : chain) nodeOrder.add(cp.getNodeId());
        out.append("## 场景 1 : 检查点链（健康全量运行）\n");
        out.append("- checkpoint 数 = ").append(chain.size()).append("，nodeId 链 = ").append(nodeOrder).append("\n");
        out.append("- 外部账本 = ").append(LEDGER).append("\n\n");

        // scenario 2: re-invoke same threadId after completion (resume semantics)
        int before = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        int traceBefore = TRACE.size();
        CompiledGraph g1f = compile(saver1);
        Checkpoint newest1 = saver1.list(c1).iterator().next(); // list 为新→旧序
        RunnableConfig c1r = RunnableConfig.builder().threadId("t1").checkPointId(newest1.getId()).build();
        g1f.invoke(Map.of(), c1r);
        int after = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        out.append("## 场景 2 : 完成后以【显式 checkPointId】再 invoke（C1 前缀续跑）\n");
        out.append("- 外部账本增量 = ").append(after - before)
           .append("，轨迹增量 = ").append(TRACE.size() - traceBefore)
           .append(" → ").append((after - before) == 0 && TRACE.size() == traceBefore ? "PASS（零重复执行）" : "观察：非零（见下）").append("\n\n");

        // scenario 2b: BARE invoke (no checkPointId) after completion : safety observation
        reset();
        MemorySaver saver1b = new MemorySaver();
        CompiledGraph gb = compile(saver1b);
        RunnableConfig cb = cfg("tb");
        gb.invoke(Map.of("q", 1), cb);
        int b4 = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        CompiledGraph gbf = compile(saver1b);
        gbf.invoke(Map.of(), cb);  // 裸 invoke：不带 checkPointId
        int af = LEDGER.values().stream().mapToInt(Integer::intValue).sum();
        out.append("## 场景 2b : 完成后【裸 invoke】（不带 checkPointId，同 threadId）\n");
        out.append("- 外部账本增量 = ").append(af - b4).append("，轨迹 = ").append(TRACE)
           .append(" → 全图静默重跑（安全观测：文档只描述了带 checkPointId 的重放路径）\n\n");

        // scenario 3: crash after effect + re-invoke same threadId
        reset();
        LEDGER.put("injectCrash", 1);
        MemorySaver saver2 = new MemorySaver();
        CompiledGraph g2 = compile(saver2);
        RunnableConfig c2 = cfg("t2");
        try {
            g2.invoke(Map.of("q", 1), c2);
            out.append("## 场景 3 : 崩溃注入未生效（意外）\n");
        } catch (RuntimeException expected) {
            int crashPointCheckpoints = saver2.list(c2).size();
            int ledgerAtCrash = LEDGER.getOrDefault("node:c", 0);
            CompiledGraph g2f = compile(saver2);
            Checkpoint newest2 = saver2.list(c2).iterator().next();
            RunnableConfig c2r = RunnableConfig.builder().threadId("t2").checkPointId(newest2.getId()).build();
            g2f.invoke(Map.of(), c2r);
            int ledgerAfter = LEDGER.getOrDefault("node:c", 0);
            int checkpointsAfter = saver2.list(c2).size();
            out.append("## 场景 3 : 副作用后崩溃 + 【显式 checkPointId】resume（SIGKILL-after-effect 类）\n");
            out.append("- 崩溃时：checkpoint 数 = ").append(crashPointCheckpoints)
               .append("，node:c 已生效 = ").append(ledgerAtCrash).append("\n");
            out.append("- 再 invoke 后：node:c 生效 = ").append(ledgerAfter)
               .append("（").append(ledgerAfter > ledgerAtCrash ? "重跑崩溃节点 → at-least-once" : "未重复 → exactly-once 语义").append("）\n");
            out.append("- resume 后 checkpoint 链长 = ").append(checkpointsAfter).append("（崩溃时 ").append(crashPointCheckpoints).append("，单调不回退）\n");
            out.append("- 外部账本终态 = ").append(new TreeMap<>(LEDGER)).append("\n");
            out.append("- 分类：文档明示「checkpoint_id 之后的所有步骤都将被执行」（java2ai.com persistence 文档），重跑=文档化行为 → gap\n\n");
        }

        // scenario 4: determinism
        reset();
        MemorySaver sa = new MemorySaver(), sb = new MemorySaver();
        compile(sa).invoke(Map.of("q", 1), cfg("d1"));
        List<String> ta = new ArrayList<>(TRACE);
        reset();
        compile(sb).invoke(Map.of("q", 1), cfg("d2"));
        List<String> tb = new ArrayList<>(TRACE);
        boolean det = ta.equals(tb) && sa.list(cfg("d1")).size() == sb.list(cfg("d2")).size();
        out.append("## 场景 4 : 双全量运行确定性（C6）\n");
        out.append("- 轨迹一致 = ").append(ta.equals(tb))
           .append("，checkpoint 数一致 = ").append(sa.list(cfg("d1")).size() == sb.list(cfg("d2")).size())
           .append(" → ").append(det ? "PASS" : "FAIL").append("\n");

        System.out.println(out);
        java.nio.file.Files.writeString(java.nio.file.Path.of("saa-driver-result.md"), out.toString());
    }

    static void reset() { LEDGER.clear(); TRACE.clear(); }
}
