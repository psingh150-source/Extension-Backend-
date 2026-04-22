-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ENUM types
CREATE TYPE user_role AS ENUM ('USER', 'ADMIN');
CREATE TYPE message_priority AS ENUM ('LOW', 'MEDIUM', 'HIGH');

-- Users table
CREATE TABLE users (
                       id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                       name                 VARCHAR(255)        NOT NULL,
                       email                VARCHAR(255) UNIQUE NOT NULL,
                       role                 user_role           NOT NULL DEFAULT 'USER',
                       google_access_token  TEXT,
                       google_refresh_token TEXT,
                       picture_url          VARCHAR(500),
                       created_at           TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                       updated_at           TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role  ON users(role);

-- Admin messages table
CREATE TABLE admin_messages (
                                id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                title            VARCHAR(500)     NOT NULL,
                                description      TEXT             NOT NULL,
                                priority         message_priority NOT NULL DEFAULT 'MEDIUM',
                                created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                updated_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                created_by_admin UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                target_user_id   UUID             REFERENCES users(id) ON DELETE CASCADE,
                                is_broadcast     BOOLEAN          NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_admin_messages_created_by ON admin_messages(created_by_admin);
CREATE INDEX idx_admin_messages_target     ON admin_messages(target_user_id);
CREATE INDEX idx_admin_messages_broadcast  ON admin_messages(is_broadcast);
CREATE INDEX idx_admin_messages_priority   ON admin_messages(priority);

-- User message status table
CREATE TABLE user_message_status (
                                     id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                     user_id    UUID    NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
                                     message_id UUID    NOT NULL REFERENCES admin_messages(id) ON DELETE CASCADE,
                                     is_read    BOOLEAN NOT NULL DEFAULT FALSE,
                                     read_at    TIMESTAMP WITH TIME ZONE,
                                     created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                                     UNIQUE (user_id, message_id)
);

CREATE INDEX idx_ums_user_id    ON user_message_status(user_id);
CREATE INDEX idx_ums_message_id ON user_message_status(message_id);
CREATE INDEX idx_ums_is_read    ON user_message_status(is_read);

-- Auto-update updated_at trigger function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_admin_messages_updated_at
    BEFORE UPDATE ON admin_messages
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
