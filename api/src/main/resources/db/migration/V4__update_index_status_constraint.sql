-- Drop the old constraint
ALTER TABLE index_status DROP CONSTRAINT IF EXISTS index_status_status_check;

-- Update existing records to uppercase BEFORE adding the new constraint
UPDATE index_status SET status = 'PENDING' WHERE status = 'not_started';
UPDATE index_status SET status = 'IN_PROGRESS' WHERE status = 'in_progress';
UPDATE index_status SET status = 'COMPLETED' WHERE status = 'completed';
UPDATE index_status SET status = 'FAILED' WHERE status = 'error';
UPDATE index_status SET status = 'FAILED' WHERE status = 'failed';

-- Add new constraint with uppercase status values
ALTER TABLE index_status ADD CONSTRAINT index_status_status_check 
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'));

