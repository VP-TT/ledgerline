-- Indexes for the retention job (deletes in small batches by age).
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
CREATE INDEX outbox_done_idx ON outbox_events (processed_at) WHERE status = 'DONE';
CREATE INDEX gateway_webhook_events_received_idx ON gateway_webhook_events (received_at);
