CREATE TABLE tb_model_metadata_cache (
    provider text NOT NULL,
    base_url text NOT NULL,
    model_id text NOT NULL,
    context_window_tokens integer NOT NULL CHECK (context_window_tokens > 0),
    source text NOT NULL,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (provider, base_url, model_id)
);
