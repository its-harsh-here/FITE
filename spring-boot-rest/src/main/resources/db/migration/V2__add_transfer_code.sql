ALTER TABLE transfer_entity ADD COLUMN transfer_code VARCHAR(32);
CREATE UNIQUE INDEX uq_transfer_code ON transfer_entity (transfer_code);
