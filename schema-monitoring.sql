-- exam_monitoring database schema
-- Run this while connected to exam_monitoring database

CREATE TABLE IF NOT EXISTS system_metrics (
    id BIGSERIAL PRIMARY KEY,
    metric_name VARCHAR(100) NOT NULL,
    metric_value DECIMAL(20,2),
    service_name VARCHAR(50),
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_metrics_name_time ON system_metrics(metric_name, recorded_at);
CREATE INDEX idx_metrics_service ON system_metrics(service_name);
