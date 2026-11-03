ALTER TABLE knowledge_base ALTER COLUMN taxonomy_relpath DROP NOT NULL;
ALTER TABLE knowledge_base ALTER COLUMN inbox_relpath DROP NOT NULL;
ALTER TABLE knowledge_base ALTER COLUMN taxonomy_relpath DROP DEFAULT;
ALTER TABLE knowledge_base ALTER COLUMN inbox_relpath DROP DEFAULT;
ALTER TABLE knowledge_base DROP COLUMN git_remote;
ALTER TABLE knowledge_base DROP COLUMN branch;
ALTER TABLE note DROP COLUMN git_commit_sha;
COMMENT ON COLUMN knowledge_base.kind IS
    'INTERNAL_DIR — нейтральная база notes/, EXTERNAL_OBSIDIAN — база с контрактом';
