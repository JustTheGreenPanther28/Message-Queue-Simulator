-- Runs once, when the Postgres volume is first created.
-- message_db is created by POSTGRES_DB; each microservice owns its own database.
CREATE DATABASE notification_db;
