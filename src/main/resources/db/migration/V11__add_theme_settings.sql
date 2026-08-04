ALTER TABLE user_settings ADD COLUMN theme_mode VARCHAR(16) NOT NULL DEFAULT 'system';
ALTER TABLE user_settings ADD COLUMN theme_color VARCHAR(16) NULL;
