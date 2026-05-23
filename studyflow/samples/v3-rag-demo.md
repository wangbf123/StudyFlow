# StudyFlow V3 RAG Demo

Redis is fast mainly because it stores data in memory, avoids most disk IO on the hot read path, and uses efficient data structures.

Redis uses an event loop and IO multiplexing to handle many network connections with low overhead. The single-threaded command execution model avoids many lock-contention problems inside the core command path.

Redis is commonly used for login sessions, rate limiting, distributed locks, counters, leaderboards, and hot document cache.

In StudyFlow, Redis is used for fixed-window API rate limiting. Redisson is used to protect duplicate document upload and parsing for the same file MD5.

Elasticsearch stores parsed document chunks. During RAG question answering, StudyFlow searches Elasticsearch for relevant chunks in the current knowledge base, builds a context prompt, calls an AI client, and saves citations for every answer.
