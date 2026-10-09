#!/usr/bin/env python3
"""Evaluate real BGE retrieval on a dedicated Elasticsearch index or the Java API.

This is a small, explicitly labeled example dataset, not an independent blind benchmark.
No mock embeddings, fabricated scores, credentials, or global mode mutations are used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
DEFAULT_MODES = ("BM25", "VECTOR", "RRF", "RRF_LEXICAL", "LEGACY")


def http(base, path, body=None, token=None, method=None, token_prefix="Bearer "):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = token_prefix + token
    request = Request(base.rstrip("/") + path, data=None if body is None else json.dumps(body).encode(),
                      headers=headers, method=method or ("POST" if body is not None else "GET"))
    with urlopen(request, timeout=120) as response:
        return json.load(response)


def rrf(lexical, semantic, constant=60):
    scores = {}
    for ranking in (lexical, semantic):
        seen = set()
        for rank, item in enumerate(ranking, 1):
            if item not in seen:
                scores[item] = scores.get(item, 0) + 1 / (constant + rank)
                seen.add(item)
    return scores


def lexical_coverage(question, content):
    def normalize(value):
        return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).lower()).strip()
    query = normalize(question)
    terms = set()
    for raw in re.findall(r"[^\W_]+", query, flags=re.UNICODE):
        if len(raw) >= 2:
            terms.add(raw)
        if any("\u4e00" <= char <= "\u9fff" for char in raw):
            terms.update(raw)
    if not terms:
        return 0
    normalized = normalize(content)
    return min(1, sum(term in normalized for term in terms) / len(terms)
               + (0.15 if query in normalized else 0))


def metrics(ranking, gold, top_k):
    relevant = set(gold)
    if not relevant:
        raise ValueError("Retrieval metrics require nonempty relevance labels")
    selected = ranking[:top_k]
    recall = len(set(selected) & relevant) / len(relevant)
    reciprocal = next((1 / rank for rank, item in enumerate(selected, 1) if item in relevant), 0)
    dcg = sum(1 / math.log2(rank + 1) for rank, item in enumerate(selected, 1) if item in relevant)
    ideal = sum(1 / math.log2(rank + 1) for rank in range(1, min(len(relevant), top_k) + 1))
    return {"recall": recall, "mrr": reciprocal, "ndcg": dcg / ideal}


class ElasticsearchExperiment:
    def __init__(self, args, dataset):
        self.args, self.dataset = args, dataset
        self.base, self.index = args.es_url, args.index
        self.sidecar_token = os.getenv("BGE_API_KEY")
        self.health = http(args.embedding_url, "/health", token=self.sidecar_token)
        if (not self.health.get("ready") or self.health.get("dimension") != 512
                or not re.fullmatch(r"BAAI/bge-small-zh-v1\.5@[0-9a-f]{40}", self.health.get("model", ""))):
            raise ValueError("A real pinned BGE model is required; mock providers are not valid for quality evaluation")
        self.model = self.health["model"]
        self.version = http(self.base, "/")["version"]["number"]
        self.vectors = {}
        self.contents = {item["id"]: item["content"] for item in dataset["corpus"]}
        self.filters = [{"term": {"userId": args.user_id}}, {"term": {"knowledgeBaseId": args.knowledge_base_id}}]

    def embed(self, texts, kind):
        response = http(self.args.embedding_url, "/embeddings",
                        {"model": self.model, "input": texts, "input_type": kind}, self.sidecar_token)
        if response.get("model") != self.model:
            raise ValueError("Embedding model changed during evaluation")
        return [entry["embedding"] for entry in sorted(response["data"], key=lambda entry: entry["index"])]

    def prepare(self):
        if not re.fullmatch(r"studyflow_eval_[a-z0-9_-]+", self.index):
            raise ValueError("Experiment indices must start with studyflow_eval_; application indices are protected")
        # Index creation is exclusive: never overwrite an existing experiment or user index.
        mapping = json.loads((ROOT.parent / "studyflow/src/main/resources/search/chunk-index.json").read_text())
        http(self.base, "/" + self.index, mapping, method="PUT")
        corpus = self.dataset["corpus"]
        for start in range(0, len(corpus), 16):
            batch = corpus[start:start + 16]
            embeddings = self.embed([item["content"] for item in batch], "document")
            for item, vector in zip(batch, embeddings, strict=True):
                self.vectors[item["id"]] = vector
                document = {"chunkId": item["id"], "documentId": item["id"], "chunkIndex": 0,
                            "knowledgeBaseId": self.args.knowledge_base_id, "userId": self.args.user_id,
                            "generation": 1, "fileName": item["title"], "pageNumber": 1,
                            "parserType": "AUTHORED_NOTE", "content": item["content"], "embedding": vector,
                            "embeddingModel": self.model, "embeddingDim": 512}
                http(self.base, f"/{self.index}/_doc/{item['id']}", document, method="PUT")
        # Identical content owned by another user and knowledge base exercises native prefiltering.
        first = corpus[0]
        for offset, user, kb in ((100000, self.args.user_id + 1, self.args.knowledge_base_id),
                                 (200000, self.args.user_id, self.args.knowledge_base_id + 1)):
            document = {"chunkId": offset + first["id"], "documentId": offset + first["id"], "chunkIndex": 0,
                        "knowledgeBaseId": kb, "userId": user, "generation": 1, "fileName": "isolation probe",
                        "content": first["content"], "embedding": self.vectors[first["id"]],
                        "embeddingModel": self.model, "embeddingDim": 512}
            http(self.base, f"/{self.index}/_doc/{offset}", document, method="PUT")
        http(self.base, f"/{self.index}/_refresh", method="POST")

    def run(self, question, mode):
        start = time.perf_counter()
        lexical, semantic, raw_bm25, raw_vector = [], [], {}, {}
        limit = self.args.candidates
        if mode != "VECTOR":
            response = http(self.base, f"/{self.index}/_search", {"size": limit,
                            "query": {"bool": {"filter": self.filters,
                                                "must": [{"match": {"content": question}}]}}})
            for hit in response["hits"]["hits"]:
                item = hit["_source"]["chunkId"]
                lexical.append(item)
                raw_bm25[item] = hit["_score"]
        if mode != "BM25":
            query_vector = self.embed([question], "query")[0]
            if mode == "LEGACY":
                # All 24 corpus items fit inside the original 1000-item MySQL scan limit.
                for item, vector in self.vectors.items():
                    cosine = sum(a * b for a, b in zip(query_vector, vector, strict=True))
                    raw_vector[item] = max(0, min(1, (cosine + 1) / 2))
                semantic = sorted(raw_vector, key=lambda item: (-raw_vector[item], item))[:limit]
            else:
                response = http(self.base, f"/{self.index}/_search", {"size": limit, "knn": {
                    "field": "embedding", "query_vector": query_vector, "k": limit,
                    "num_candidates": max(limit, self.args.knn_candidates),
                    "filter": {"bool": {"filter": self.filters + [
                        {"term": {"embeddingModel": self.model}}, {"term": {"embeddingDim": 512}}]}}}})
                for hit in response["hits"]["hits"]:
                    item = hit["_source"]["chunkId"]
                    semantic.append(item)
                    raw_vector[item] = hit["_score"]
        combined = set(lexical) | set(semantic)
        if any(item not in self.contents for item in combined):
            raise AssertionError("User/knowledge-base isolation failed")
        if mode == "BM25":
            ranking = lexical
        elif mode == "VECTOR":
            ranking = semantic
        else:
            scores = rrf(lexical, semantic, self.args.rrf_constant)
            if mode == "RRF_LEXICAL":
                scores = {item: .9 * value * (self.args.rrf_constant + 1) / 2
                          + .1 * lexical_coverage(question, self.contents[item]) for item, value in scores.items()}
            elif mode == "LEGACY":
                maximum = max(raw_bm25.values(), default=0)
                scores = {}
                for item in combined:
                    lexical_score = raw_bm25.get(item, 0) / maximum if maximum else 0
                    numerator = .45 * lexical_score + .45 * raw_vector.get(item, 0)
                    numerator += .1 * lexical_coverage(question, self.contents[item])
                    denominator = (.45 if item in raw_bm25 else 0) + (.45 if item in raw_vector else 0) + .1
                    scores[item] = numerator / denominator
            ranking = sorted(scores, key=lambda item: (-scores[item], item))
        return ranking[:self.args.top_k], (time.perf_counter() - start) * 1000


class BackendExperiment:
    def __init__(self, args, dataset):
        self.args = args
        self.token = os.environ["STUDYFLOW_EVAL_TOKEN"]
        self.manifest = json.loads(Path(args.manifest).read_text())
        self.version = "Java API (ES version recorded in deployment)"
        self.model = self.manifest["embedding_model"]
        if not re.fullmatch(r"BAAI/bge-small-zh-v1\.5@[0-9a-f]{40}", self.model):
            raise ValueError("Backend manifest must identify the real pinned model")
        self.ids = {int(database_id): int(corpus_id) for corpus_id, database_id in self.manifest["chunk_ids"].items()}

    def prepare(self):
        pass

    def run(self, question, mode):
        query = urlencode({"knowledgeBaseId": self.args.knowledge_base_id, "keyword": question,
                           "size": self.args.top_k, "mode": mode})
        start = time.perf_counter()
        response = http(self.args.backend_url, "/api/search?" + query, token=self.token, token_prefix="")
        if response.get("code") != 0:
            raise RuntimeError("Backend retrieval returned a failure")
        ranking = [self.ids[int(item["chunkId"])] for item in response["data"]]
        return ranking, (time.perf_counter() - start) * 1000


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default=str(ROOT / "data/study-notes.json"))
    parser.add_argument("--es-url", default="http://127.0.0.1:9200")
    parser.add_argument("--embedding-url", default="http://127.0.0.1:8091")
    parser.add_argument("--index", default="studyflow_eval_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"))
    parser.add_argument("--backend-url", help="Evaluate Java API instead of dedicated ES experiment")
    parser.add_argument("--manifest", help="JSON with embedding_model and corpus_id→database_chunk_id mapping")
    parser.add_argument("--user-id", type=int, default=900001)
    parser.add_argument("--knowledge-base-id", type=int, default=900001)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--candidates", type=int, default=40)
    parser.add_argument("--knn-candidates", type=int, default=200)
    parser.add_argument("--rrf-constant", type=int, default=60)
    parser.add_argument("--split", choices=("dev", "test", "all"), default="test")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--modes", nargs="+", choices=DEFAULT_MODES, default=DEFAULT_MODES)
    parser.add_argument("--output", default=str(ROOT / "reports/local-evaluation"))
    args = parser.parse_args()
    if args.top_k < 1 or args.candidates < args.top_k or args.repeats < 1:
        parser.error("Require top-k>=1, candidates>=top-k and repeats>=1")
    if args.backend_url and not args.manifest:
        parser.error("Backend mode requires --manifest")
    data_bytes = Path(args.dataset).read_bytes()
    dataset = json.loads(data_bytes)
    ids = {item["id"] for item in dataset["corpus"]}
    questions = [item for item in dataset["questions"] if args.split == "all" or item["split"] == args.split]
    if not questions or any(not q["relevant_ids"] or not set(q["relevant_ids"]) <= ids for q in questions):
        raise ValueError("Invalid/nonempty relevance labels or empty split")
    experiment = BackendExperiment(args, dataset) if args.backend_url else ElasticsearchExperiment(args, dataset)
    experiment.prepare()
    report = {"generated_at_utc": datetime.now(timezone.utc).isoformat(), "status": "MEASURED",
              "dataset_sha256": hashlib.sha256(data_bytes).hexdigest(), "dataset_purpose": dataset["purpose"],
              "scope": "Java API with database validation" if args.backend_url else "ES native retrieval and identical fusion formula; not Java end-to-end",
              "model": experiment.model, "elasticsearch_version": experiment.version,
              "corpus_size": len(dataset["corpus"]), "question_count": len(questions), "split": args.split,
              "top_k": args.top_k, "candidates": args.candidates, "knn_candidates": args.knn_candidates,
              "rrf_constant": args.rrf_constant, "modes": {}, "rows": []}
    for mode in args.modes:
        experiment.run(questions[0]["question"], mode)  # Explicit unmeasured warmup.
        values, latencies = [], []
        for question in questions:
            for repeat in range(args.repeats):
                ranking, latency = experiment.run(question["question"], mode)
                latencies.append(latency)
                if repeat == 0:
                    result = metrics(ranking, question["relevant_ids"], args.top_k)
                    values.append(result)
                    report["rows"].append({"question_id": question["id"], "mode": mode,
                                           "ranking": ranking, "gold": question["relevant_ids"], **result})
        sorted_latencies = sorted(latencies)
        report["modes"][mode] = {name: statistics.mean(value[name] for value in values)
                                  for name in ("recall", "mrr", "ndcg")}
        report["modes"][mode].update({"latency_p50_ms": statistics.median(latencies),
                                      "latency_p95_ms": sorted_latencies[math.ceil(.95 * len(latencies)) - 1],
                                      "latency_samples": len(latencies)})
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_suffix(".json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    lines = ["# 检索示例评测（实测）", "", dataset["purpose"], "", f"范围：{report['scope']}",
             f"模型：`{experiment.model}`；Elasticsearch：{experiment.version}",
             f"数据 SHA-256：`{report['dataset_sha256']}`", "",
             f"{report['corpus_size']} 个分片，{len(questions)} 道 {args.split} 问题，K={args.top_k}。",
             "延迟包括查询 embedding（BM25 无该调用）、ES 查询与融合；单进程顺序运行，已预热，未测高并发。", "",
             "| 模式 | Recall@K | MRR@K | nDCG@K | p50 ms | p95 ms |",
             "|---|---:|---:|---:|---:|---:|"]
    for mode, values in report["modes"].items():
        lines.append(f"| {mode} | {values['recall']:.4f} | {values['mrr']:.4f} | {values['ndcg']:.4f} | "
                     f"{values['latency_p50_ms']:.2f} | {values['latency_p95_ms']:.2f} |")
    lines += ["", "此规模不足以证明线上泛化能力或规模性能；高分也可能源于问题与资料编写方式。",
              "保留逐题排名，扩大真实资料及独立标注后再报告简历提升数字；不据示例集宣称最优参数。", ""]
    output.with_suffix(".md").write_text("\n".join(lines))
    print(json.dumps({"report": str(output.with_suffix('.json')), "modes": report["modes"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
