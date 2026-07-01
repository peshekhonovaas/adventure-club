-- Enable the pgvector extension (shipped in the pgvector/pgvector image).
-- Must run before any column below uses the `vector` type.
CREATE EXTENSION IF NOT EXISTS vector;

-- pgvector table used by Spring AI's PgVectorStore
-- all-MiniLM-L6-v2 (local ONNX embedding model) produces 384-dimensional embeddings
CREATE TABLE IF NOT EXISTS vector_store (
                                            id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                            content   TEXT        NOT NULL,
                                            metadata  JSONB,
                                            embedding vector(384)
);

-- HNSW index — fast approximate nearest-neighbour search
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops);