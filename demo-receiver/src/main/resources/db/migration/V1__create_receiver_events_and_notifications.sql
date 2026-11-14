CREATE TABLE hookfault_receiver.processed_events (
    run_id UUID NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, event_id)
);

CREATE TABLE hookfault_receiver.welcome_notifications (
    run_id UUID NOT NULL,
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, event_id),
    FOREIGN KEY (run_id, event_id)
        REFERENCES hookfault_receiver.processed_events (run_id, event_id)
);
