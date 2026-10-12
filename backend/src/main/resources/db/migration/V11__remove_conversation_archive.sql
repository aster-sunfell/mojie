-- All conversations share one history list; preserve records and their original model configuration.
ALTER TABLE tb_conversation DROP COLUMN status;
