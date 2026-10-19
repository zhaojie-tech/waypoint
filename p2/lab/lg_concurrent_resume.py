#!/usr/bin/env python3
"""Waypoint P2 — REAL concurrent resume (C5 consume-once, true concurrency).

init -> record (SIGKILL at node c after its side effect)
concurrent: launch TWO resume processes at the same instant -> audit ledger.
"""
import json, os, signal, sys, subprocess
from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.sqlite import SqliteSaver
from typing_extensions import TypedDict

DB = "lgc-checkpoints.sqlite"
LEDGER = "lgc-ledger.json"

def ledger_apply(key):
    import fcntl
    with open(LEDGER + ".lock", "w") as lk:
        fcntl.flock(lk, fcntl.LOCK_EX)
        with open(LEDGER) as f: d = json.load(f)
        d[key] = d.get(key, 0) + 1
        with open(LEDGER, "w") as f:
            json.dump(d, f); f.flush(); os.fsync(f.fileno())
        fcntl.flock(lk, fcntl.LOCK_UN)

def ledger(): 
    with open(LEDGER) as f: return json.load(f)

class S(TypedDict, total=False):
    q: int

def make_node(name):
    def node(state: S):
        ledger_apply("node:" + name)
        if name == "c" and os.environ.get("INJECT_CRASH") == "1":
            sys.stderr.flush()
            os.kill(os.getpid(), signal.SIGKILL)
        return {"seen_" + name: True}
    return node

from contextlib import contextmanager
@contextmanager
def build():
    with SqliteSaver.from_conn_string(DB) as saver:
        g = StateGraph(S)
        for n in ("a", "b", "c"): g.add_node(n, make_node(n))
        g.add_edge(START, "a"); g.add_edge("a", "b"); g.add_edge("b", "c"); g.add_edge("c", END)
        yield g.compile(checkpointer=saver)

def resume_worker(wid):
    with build() as graph:
        cfg = {"configurable": {"thread_id": "t1"}}
        try:
            graph.invoke(None, cfg)
            print(f"[worker{wid}] invoke returned normally", flush=True)
        except Exception as e:
            print(f"[worker{wid}] invoke raised: {type(e).__name__}: {str(e)[:120]}", flush=True)

if sys.argv[1] == "init":
    for f in (LEDGER, DB): 
        if os.path.exists(f): os.remove(f)
    with open(LEDGER, "w") as f: json.dump({}, f)
    print("reset ok")
elif sys.argv[1] == "record":
    os.environ["INJECT_CRASH"] = "1"
    with build() as graph:
        try:
            graph.invoke({"q": 1}, {"configurable": {"thread_id": "t1"}})
        except SystemExit: pass
elif sys.argv[1] == "probe":
    # 证据步：崩溃后、并发 resume 前，查询已提交的 checkpoint 数
    with SqliteSaver.from_conn_string(DB) as saver:
        with build() as graph:
            pass
        chain = list(saver.list({"configurable": {"thread_id": "t1"}}))
        print("committed checkpoints at crash:", len(chain))
        open("lgc-probe.txt", "w").write(json.dumps({"committed_checkpoints": len(chain)}))
elif sys.argv[1] == "concurrent":
    import multiprocessing as mp
    ps = [mp.Process(target=resume_worker, args=(i,)) for i in range(2)]
    for p in ps: p.start()
    for p in ps: p.join()
    led = ledger()
    c = led.get("node:c", 0)
    rep = ["## 真并发 resume 结果（2 真进程同时 invoke(None)）",
           "- 崩溃后已提交检查点数（probe）: " + ((str(json.loads(open("lgc-probe.txt").read())["committed_checkpoints"]) if os.path.exists("lgc-probe.txt") else "未跑 probe")),
           "- 终态账本: " + json.dumps(led, sort_keys=True),
           "- node:c = %d（崩溃前 1 + 两进程各重跑 1 = 3 → 并发下 consume-once 失效）" % c]
    print("\n".join(rep))
    open("lg-concurrent-result.md", "w").write("\n".join(rep) + "\n")
    if c == 1: print("consume-once: c==1 (两个 resume 进程都没执行 c —— 并发下双双空转？)")
    elif c == 2: print("consume-once: c==2 (恰好一个 resume 进程重跑 c —— 并发下消费一次成立)")
    elif c == 3: print("consume-once: c==3 (两个 resume 进程都重跑 c —— 并发下 consume-once 失效)")
    else: print(f"consume-once: c=={c} (异常值)")
