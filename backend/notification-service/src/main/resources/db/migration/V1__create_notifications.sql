-- One row per notification. The identity link is user_id, the auth-service user id
-- and JWT subject, so the service never has to resolve an email to find its owner.
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(30)  NOT NULL,
    title       VARCHAR(160) NOT NULL,
    message     VARCHAR(500) NOT NULL,
    order_id    BIGINT,
    delivery_id BIGINT,
    level       VARCHAR(10)  NOT NULL DEFAULT 'INFO',
    read        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

-- The notification centre is always read as "this user's notifications, newest first".
CREATE INDEX idx_notifications_user_created_at ON notifications (user_id, created_at DESC);

-- Backs the unread badge, which is the only query that is not scoped to a page.
CREATE INDEX idx_notifications_read ON notifications (read);

COMMENT ON COLUMN notifications.user_id IS 'auth-service user id: the identity link carried by the JWT subject';
COMMENT ON COLUMN notifications.type IS 'business trigger that produced the notification';
COMMENT ON COLUMN notifications.level IS 'presentation hint for the UI: INFO, SUCCESS, WARNING or ERROR';
COMMENT ON COLUMN notifications.read IS 'false until the owner reads the notification';
