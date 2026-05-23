# StudyFlow V4 Demo

Redis is fast because most operations happen in memory. Compared with disk-based databases, memory access greatly reduces latency for hot data.

Redis also uses an event loop and IO multiplexing to serve many network connections efficiently. Its core command execution path is single-threaded, which avoids many lock contention costs inside Redis itself.

In backend systems, Redis is often used for login sessions, API rate limiting, distributed locks, counters, leaderboards, and hot cache.

StudyFlow uses Redis for fixed-window rate limiting. The upload API and RAG question API can be protected by Redis counters to avoid abuse.

StudyFlow uses Redisson distributed locks around the document MD5. This prevents duplicate parsing when the same file is uploaded or parsed concurrently.

StudyFlow stores original files in MinIO, structured metadata in MySQL, parsed text chunks in MySQL, and searchable chunk indexes in Elasticsearch.

During RAG answering, StudyFlow first retrieves relevant chunks from Elasticsearch by knowledge base, then builds a prompt from those chunks, calls the AI client, saves the assistant answer, and records citations in qa_citation.
