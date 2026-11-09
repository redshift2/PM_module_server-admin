-- Apply once to existing databases before deploying this code.
-- H2-compatible SQL; back up your database first.
ALTER TABLE server_admin_job ADD COLUMN IF NOT EXISTS route_data VARCHAR(32);
ALTER TABLE server_admin_event_rule ADD COLUMN IF NOT EXISTS route_data VARCHAR(32);
ALTER TABLE server_admin_event_action ADD COLUMN IF NOT EXISTS route_data VARCHAR(32);
UPDATE server_admin_job SET route_data = CASE
  WHEN job_type IN ('CAD_TO_GLB', 'CAD_TO_X3D') THEN '3D_VIEWER'
  WHEN job_type = 'CAD_PROPERTIES' THEN 'METADATA'
  ELSE 'JOB_RESULTS' END WHERE route_data IS NULL;
UPDATE server_admin_event_rule SET route_data = CASE
  WHEN destination IN ('CAD_TO_GLB', 'CAD_TO_X3D') AND action_type = 'SUBMIT_JOB' THEN '3D_VIEWER'
  WHEN destination = 'CAD_PROPERTIES' AND action_type = 'SUBMIT_JOB' THEN 'METADATA'
  ELSE 'JOB_RESULTS' END WHERE route_data IS NULL;
UPDATE server_admin_event_action SET route_data = CASE
  WHEN destination IN ('CAD_TO_GLB', 'CAD_TO_X3D') AND action_type = 'SUBMIT_JOB' THEN '3D_VIEWER'
  WHEN destination = 'CAD_PROPERTIES' AND action_type = 'SUBMIT_JOB' THEN 'METADATA'
  ELSE 'JOB_RESULTS' END WHERE route_data IS NULL;
