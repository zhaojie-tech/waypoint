# P2 lab : 真实框架驱动器

## langgraph4j 1.9.5（src/）
```bash
mvn -f pom.xml dependency:copy-dependencies -DoutputDirectory=libs
javac -cp "libs/*" -d classes src/LangGraph4jDriver.java
java -cp "classes:libs/*" LangGraph4jDriver      # 输出 driver-result.md
```
## spring-ai-alibaba-graph-core 1.1.2.4（src2/）
```bash
mvn -f pom-saa.xml dependency:copy-dependencies -DoutputDirectory=libs-saa
javac -cp "libs-saa/*" -d classes2 src2/SaGraphDriver.java
java -cp "classes2:libs-saa/*" SaGraphDriver     # 输出 saa-driver-result.md
```
（src = langgraph4j 驱动器，src2 = SAA 驱动器）

## LangGraph Python（真 SIGKILL，需 root 外条件：无）
```bash
python3 -m venv .venv-py && ./.venv-py/bin/pip install -r requirements.txt
./.venv-py/bin/python langgraph_sigkill_driver.py init
INJECT_CRASH=1 ./.venv-py/bin/python langgraph_sigkill_driver.py record   # 进程自杀 exit 137
./.venv-py/bin/python langgraph_sigkill_driver.py resume                  # 输出 langgraph-sigkill-result.md
./.venv-py/bin/python lg_concurrent_resume.py init
./.venv-py/bin/python lg_concurrent_resume.py record
./.venv-py/bin/python lg_concurrent_resume.py probe       # 崩溃后已提交检查点数（#10 证据）
./.venv-py/bin/python lg_concurrent_resume.py concurrent  # 双进程同时 resume → lg-concurrent-result.md
```

## 真实 SSE 捕获（P4 冒烟输入）

```bash
curl -s -N http://<litellm-host>:<port>/v1/chat/completions \
  -H "Authorization: Bearer <key>" -H "Content-Type: application/json" \
  -d '{"model":"<model>","stream":true,"max_tokens":150,"messages":[{"role":"user","content":"..."}]}' \
  -o real-llm-sse.txt
```
key/端点不入仓：自备网关与虚拟 key（本仓不提供）。
