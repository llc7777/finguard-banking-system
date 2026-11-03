INSERT INTO accounts (account_number, balance, status, created_at)
SELECT 'FG-' || LPAD(account_index::text, 6, '0'), 100000000, 'ACTIVE', CURRENT_TIMESTAMP
FROM generate_series(1, 20) AS account_index
ON CONFLICT (account_number) DO NOTHING;
