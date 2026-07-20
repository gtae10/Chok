-- 주의: 현재 spring.sql.init.mode=never 로 이 파일은 실행되지 않음.
-- 실제 테이블 스키마는 domain/* 엔티티 + ddl-auto=update 로 자동 생성됨.
-- 아래 정의는 엔티티(NewsSentiment 등)와 컬럼이 달라 그대로 쓰면 충돌하니
-- 참고용으로만 남겨두고, 필요 시 엔티티 기준으로 다시 작성할 것.
CREATE TABLE IF NOT EXISTS stocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100),
    market VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS price_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT,
    date DATE,
    open DECIMAL(18,4),
    high DECIMAL(18,4),
    low DECIMAL(18,4),
    close DECIMAL(18,4),
    volume BIGINT,
    FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS technical_scores (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT,
    date DATE,
    rsi DECIMAL(10,4),
    macd DECIMAL(10,4),
    signal_line DECIMAL(10,4),
    score DECIMAL(10,4),
    FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS news_sentiments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT,
    title VARCHAR(500),
    url VARCHAR(1000),
    published_at TIMESTAMP,
    sentiment_score DOUBLE,
    sentiment_label VARCHAR(20),
    FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE IF NOT EXISTS recommendations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id BIGINT,
    created_at TIMESTAMP,
    action VARCHAR(10),
    technical_score DOUBLE,
    sentiment_score DOUBLE,
    total_score DOUBLE,
    reason TEXT,
    FOREIGN KEY (stock_id) REFERENCES stocks(id)
);
