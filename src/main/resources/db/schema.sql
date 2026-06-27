CREATE DATABASE IF NOT EXISTS chok
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE chok;

CREATE TABLE IF NOT EXISTS stocks (
    ticker          VARCHAR(10)   NOT NULL PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    market          VARCHAR(10)   NOT NULL,
    market_cap      BIGINT        NOT NULL DEFAULT 0,
    base_date       VARCHAR(8)    NOT NULL,
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_market_cap (market_cap DESC)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS price_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker          VARCHAR(10)   NOT NULL,
    trade_date      DATE          NOT NULL,
    open_price      INT           NOT NULL,
    high_price      INT           NOT NULL,
    low_price       INT           NOT NULL,
    close_price     INT           NOT NULL,
    volume          BIGINT        NOT NULL,
    UNIQUE KEY uq_ticker_date (ticker, trade_date),
    INDEX idx_ticker_date (ticker, trade_date DESC),
    CONSTRAINT fk_price_stock FOREIGN KEY (ticker) REFERENCES stocks(ticker)
        ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS technical_scores (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker              VARCHAR(10)   NOT NULL,
    calc_date           DATE          NOT NULL,
    ma5                 DOUBLE,
    ma20                DOUBLE,
    ma60                DOUBLE,
    rsi                 DOUBLE,
    macd                DOUBLE,
    macd_signal         DOUBLE,
    macd_histogram      DOUBLE,
    bb_upper            DOUBLE,
    bb_lower            DOUBLE,
    bb_percent_b        DOUBLE,
    technical_score     DOUBLE,
    technical_reason    TEXT,
    UNIQUE KEY uq_ticker_calc_date (ticker, calc_date),
    CONSTRAINT fk_tech_stock FOREIGN KEY (ticker) REFERENCES stocks(ticker)
        ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS news_sentiment (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker              VARCHAR(10)   NOT NULL,
    news_date           DATE          NOT NULL,
    headline            VARCHAR(500)  NOT NULL,
    url                 VARCHAR(1000),
    sentiment_score     DOUBLE,
    sentiment_label     VARCHAR(20),
    summary             TEXT,
    analyzed_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ticker_date (ticker, news_date DESC),
    CONSTRAINT fk_news_stock FOREIGN KEY (ticker) REFERENCES stocks(ticker)
        ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recommendations (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker              VARCHAR(10)   NOT NULL,
    rec_date            DATE          NOT NULL,
    name                VARCHAR(100)  NOT NULL,
    market              VARCHAR(10)   NOT NULL,
    technical_score     DOUBLE,
    sentiment_score     DOUBLE,
    final_score         DOUBLE,
    recommendation      VARCHAR(20),
    reason              TEXT,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_ticker_rec_date (ticker, rec_date),
    INDEX idx_rec_date_score (rec_date, final_score DESC),
    CONSTRAINT fk_rec_stock FOREIGN KEY (ticker) REFERENCES stocks(ticker)
        ON DELETE CASCADE
) ENGINE=InnoDB;