CREATE TABLE transfer_entity (
    transfer_id VARCHAR(255) PRIMARY KEY,
    share_token VARCHAR(255) NOT NULL,
    file_name VARCHAR(1024) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT NOT NULL,
    chunk_size BIGINT NOT NULL,
    total_chunks INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE transfer_chunk_entity (
    id BIGSERIAL PRIMARY KEY,
    transfer_id VARCHAR(255) NOT NULL REFERENCES transfer_entity(transfer_id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    size BIGINT NOT NULL,
    checksum VARCHAR(255) NOT NULL,
    storage_key VARCHAR(1024),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_transfer_chunk UNIQUE (transfer_id, chunk_index)
);
