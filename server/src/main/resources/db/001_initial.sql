CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    profile jsonb NOT NULL,
    balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE stays (
    id text PRIMARY KEY,
    payload jsonb NOT NULL,
    active boolean NOT NULL DEFAULT false
);
CREATE TABLE saved_stays (
    account_id uuid REFERENCES accounts(id) ON DELETE CASCADE,
    stay_id text REFERENCES stays(id),
    PRIMARY KEY (account_id, stay_id)
);
CREATE TABLE quotes (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    payload jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX quotes_account ON quotes(account_id, created_at);
CREATE TABLE reservations (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    request_key text NOT NULL CHECK (length(request_key) BETWEEN 16 AND 100),
    quote_id uuid NOT NULL UNIQUE REFERENCES quotes(id),
    quote jsonb NOT NULL,
    state text NOT NULL CHECK (state IN ('pending_payment','confirmed','cancel_pending','cancelled','payment_failed')),
    created_at timestamptz NOT NULL,
    payment_deadline timestamptz NOT NULL,
    payment_intent_id text UNIQUE,
    refund_id text UNIQUE,
    refund_started_at timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    credit_returned bigint NOT NULL DEFAULT 0 CHECK (credit_returned >= 0),
    test_mode boolean NOT NULL,
    review_required boolean NOT NULL DEFAULT false,
    review_reason text,
    next_reconcile_at timestamptz NOT NULL DEFAULT now(),
    reconcile_attempts integer NOT NULL DEFAULT 0,
    UNIQUE (account_id, request_key)
);
CREATE INDEX reservations_account ON reservations(account_id, created_at DESC);
CREATE INDEX reservations_reconcile ON reservations(next_reconcile_at) WHERE NOT review_required AND state IN ('pending_payment','cancel_pending');
CREATE TABLE reserved_nights (
    stay_id text NOT NULL REFERENCES stays(id),
    night date NOT NULL,
    reservation_id uuid NOT NULL REFERENCES reservations(id),
    PRIMARY KEY (stay_id, night)
);
CREATE INDEX reserved_nights_reservation ON reserved_nights(reservation_id);
CREATE TABLE ledger (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    reservation_id uuid REFERENCES reservations(id),
    kind text NOT NULL,
    title text NOT NULL,
    amount bigint NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (reservation_id, kind)
);
CREATE INDEX ledger_account ON ledger(account_id, created_at DESC);
CREATE TABLE webhook_events (
    id text PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
