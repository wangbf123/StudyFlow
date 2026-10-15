# 可复现的混合检索实验

后端在 Elasticsearch 8.15.3 上分别执行 BM25 和带用户、知识库过滤的 HNSW kNN，再在 Java 中使用 RRF 合并排名（常数 60）。返回结果复查 MySQL 中的所有权、文档状态和 generation。`RRF_LEXICAL` 是可选的词面覆盖度重排对照，`LEGACY` 保留旧检索算法用于比较。默认使用 RRF。

向量模型使用 CPU `BAAI/bge-small-zh-v1.5`，512 维、归一化输出。`model-lock.json` 固定实测模型的完整提交 SHA；查询加入 BGE 中文检索前缀，文档不加前缀。下载或模型身份校验失败会报错；超过模型 token 上限的输入拒绝处理，需要缩小分片后重建文档。

## 启动

在仓库根目录运行，首次下载需要访问 Hugging Face。模型及其模型卡来自固定 revision 的官方模型仓库，运行不启用远程 Python 代码。

```bash
python3 -m venv retrieval-lab/.venv
retrieval-lab/.venv/bin/pip install torch==2.5.1+cpu --index-url https://download.pytorch.org/whl/cpu
retrieval-lab/.venv/bin/pip install -r retrieval-lab/requirements.txt
BGE_CACHE_DIR=/tmp/studyflow-bge-cache retrieval-lab/.venv/bin/uvicorn embedding_server:app \
  --app-dir retrieval-lab --host 127.0.0.1 --port 8091
```

`requirements.lock` 记录本次 CPU 环境的完整版本，可在相同平台上复现依赖；它不是跨平台依赖解析结果。部署时使用独立缓存目录，侧车绑定本地地址；跨主机调用通过 `BGE_API_KEY` 设置认证，并在 Java 中配置相同密钥。

Java 后端配置：

```bash
export STUDYFLOW_EMBEDDING_PROVIDER=bge-local
export STUDYFLOW_EMBEDDING_BASE_URL=http://127.0.0.1:8091
export STUDYFLOW_EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5@7999e1d3359715c523056ef9478215996d62a620
export STUDYFLOW_EMBEDDING_DIMENSION=512
export STUDYFLOW_SEARCH_INDEX=studyflow_document_chunk_v2
```

新索引使用显式 CJK 文本分析和 cosine HNSW mapping，批量索引有大小上限。旧索引不能直接复用不同 mapping；现有文档通过重建接口进入新 generation。模型或维度改变后也需创建独立索引并重建。默认 mock embedding 仅用于运行与协议测试，没有语义检索质量保证。

## 评测

```bash
python3 retrieval-lab/evaluate.py --index studyflow_eval_local \
  --output retrieval-lab/reports/local-evaluation
```

此命令创建独立评测索引并写入样例语料，会覆盖指定评测索引中的内容；不要指定应用索引。报告保留逐题排名、配置、数据摘要和 Recall@5、MRR@5、nDCG@5、p50/p95。原生 ES 实验用于算法对照；完整 Java API 实验还包含数据库校验和 HTTP 路径，通过以下参数执行：

```bash
python3 retrieval-lab/evaluate.py --backend-url http://127.0.0.1:8082 \
  --knowledge-base-id YOUR_KB_ID --user-id YOUR_USER_ID --manifest /tmp/chunk-manifest.json \
  --output retrieval-lab/reports/java-evaluation
```

登录 token 从 `STUDYFLOW_EVAL_TOKEN` 环境变量读取，不写入报告。manifest 格式为 `{"embedding_model":"模型完整身份","chunk_ids":{"语料ID":数据库分片ID}}`，分片来自按 `data/study-notes.json` 上传的语料。Java API 使用原始 Sa-Token Authorization 值。

实测报告见 `reports/initial-native-evaluation.md` 和 `reports/initial-java-evaluation.md`。当前样例包含 24 份笔记、22 道 test 问题，问题与标签由实现者编写，尚未经独立标注；顺序测量的延迟不是负载测试。可以写“实现并评测混合检索”，不能据此声称生产准确率或高并发容量。

完整 API 验证可在独立测试实例执行 `python3 retrieval-lab/verify_backend.py --backend-url http://127.0.0.1:8082`。实例使用 async MQ、extractive mock AI、OCR 关闭及上述真实 BGE；仅该测试实例关闭限流，以连续写入语料。脚本创建自己的测试账号和知识库，运行上传、检索、问答、SSE、重建、删除与跨用户验证，成功后删除自己的知识库。账号创建仍保留在测试库，测试结束可销毁整个隔离库。报告不记录密码或 token。


## 知识库工作台验收

`verify_workspace.py` 覆盖 24 份真实入库、独立摘要、当前版本聚合、游标分页、检索说明、引用以及新端点权限；可用两个实例验证 Redis 共享 token。最新报告为 `reports/workspace-java-evaluation.md`，旧 initial 报告保留原始日期。

工作台、共享会话与截图复现条件见 [升级验收](../studyflow/docs/workspace-upgrade-verification.md)。`capture_workspace.py` 使用实际浏览器和公开样例，不注入伪造数据；需要可选的 Playwright 依赖与浏览器。
