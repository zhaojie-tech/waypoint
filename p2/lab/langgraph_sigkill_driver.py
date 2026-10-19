#!/usr/bin/env python3
"""Waypoint P2 — LangGraph (Python) baseline with REAL SIGKILL.

Phases:
  record : run graph a->b->c; node c applies side effect (fsynced ledger) then
           SIGKILLs itself — process dies mid-run, page cache irrelevant for
           process state, ledger file already fsynced.
  resume : fresh process, same sqlite checkpointer, invoke(None) to resume;
           audit ledger (node:c applied how many times?) + checkpoint chain.

Ledger is the external ground truth (survives SIGKILL by fsync-before-kill).
"""
import json, os, signal, sys, sqlite3

from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.sqlite import SqliteSaver
from typing_extensions import TypedDict

DB = "lg-checkpoints.sqlite"
LEDGER = "lg-ledger.json"   # external ground truth

def ledger_apply(key):
    import fcntl
    with open(LEDGER + ".lock", "w") as lk:
        fcntl.flock(lk, fcntl.LOCK_EX)
        with open(LEDGER) as f:
            d = json.load(f)
        d[key] = d.get(key, 0) + 1
        with open(LEDGER, "w") as f:
            json.dump(d, f)
            f.flush()
            os.fsync(f.fileno())
        fcntl.flock(lk, fcntl.LOCK_UN)
    return d

def ledger_read():
    with open(LEDGER) as f:
        return json.load(f)

class S(TypedDict, total=False):
    q: int

def make_node(name):
    def node(state: S):
        if name == "c" and os.environ.get("INJECT_CRASH") == "1":
            ledger_apply("node:c")
            sys.stderr.write("[node:c] side effect applied, SIGKILLing self NOW\n")
            sys.stderr.flush()
            os.kill(os.getpid(), signal.SIGKILL)   # REAL SIGKILL — process dies here
        ledger_apply("node:" + name)
        return {"seen_" + name: True}
    return node

def build(checkpointer):
    g = StateGraph(S)
    g.add_node("a", make_node("a"))
    g.add_node("b", make_node("b"))
    g.add_node("c", make_node("c"))
    g.add_edge(START, "a"); g.add_edge("a", "b"); g.add_edge("b", "c"); g.add_edge("c", END)
    return g.compile(checkpointer=checkpointer)

def _ver():
    try:
        import importlib.metadata as im
        return im.version("langgraph")
    except Exception:
        return "unknown"

def main():
    phase = sys.argv[1]
    if phase == "init":
        with open(LEDGER, "w") as f: json.dump({}, f)
        if os.path.exists(DB): os.remove(DB)
        print("ledger + db reset")
        return
    with SqliteSaver.from_conn_string(DB) as saver:
        graph = build(saver)
        cfg = {"configurable": {"thread_id": "t1"}}
        if phase == "record":
            graph.invoke({"q": 1}, cfg)   # dies inside node c via SIGKILL
            print("UNEXPECTED: invoke returned (crash injection failed)")
        elif phase == "resume":
            # fresh process; same sqlite checkpointer; resume with None input
            graph.invoke(None, cfg)
            led = ledger_read()
            chain = list(saver.list(cfg))
            print("== resume audit ==")
            print("ledger:", led)
            print("checkpoints:", len(chain), "nodes:", [cp.metadata.get("source","?") for cp in chain][:6])
            ok_c1 = True  # resume completed the run
            rep = ["## 真 SIGKILL 驱动结果（langgraph " + _ver() + "）",
                   "- record: node:c 副作用 fsync 后进程自杀（exit 137）",
                   "- resume（新进程, SqliteSaver, invoke(None)）后账本: " + json.dumps(led, sort_keys=True),
                   "- node:c 生效次数: %d（>1 = at-least-once 重跑）" % led.get("node:c", 0),
                   "- checkpoints: %d" % len(chain)]
            rep.append("- 全部节点终态: " + str(all(led.get("node:"+n, 0) >= 1 for n in ("a","b","c"))))
            print("\n".join(rep))
            open("langgraph-sigkill-result.md", "w").write("\n".join(rep) + "\n")

if __name__ == "__main__":
    main()
