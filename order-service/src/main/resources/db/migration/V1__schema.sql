CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE TABLE coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(120) NOT NULL,
    price_cents INT NOT NULL,
    initial_stock INT NOT NULL,
    stock INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    starts_at TIMESTAMP(3) NOT NULL,
    ends_at TIMESTAMP(3) NOT NULL,
    CHECK (stock >= 0), CHECK (initial_stock >= stock), CHECK (ends_at > starts_at)
) ENGINE=InnoDB;
CREATE TABLE coupon_order (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_user_coupon UNIQUE (user_id, coupon_id),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_order_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id),
    INDEX idx_user_time (user_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE order_rejection (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
INSERT INTO coupon (title, price_cents, initial_stock, stock, starts_at, ends_at) VALUES
('Java 开发者专享 · 咖啡月卡', 990, 100, 100, NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 30 DAY),
('周末补给 · 双人轻食券', 1990, 50, 50, NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 30 DAY),
('限量体验 · 数码配件券', 2990, 20, 20, NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 30 DAY);
