-- Catálogo de zonas. Base de datos propia del servicio zones (ADR-001).
CREATE TABLE zones (
    zone_id              VARCHAR(64)      PRIMARY KEY CHECK (zone_id ~ '^[a-z0-9-]+$'),
    name                 VARCHAR(200)     NOT NULL,
    center_lat           DOUBLE PRECISION NOT NULL CHECK (center_lat BETWEEN -90 AND 90),
    center_lon           DOUBLE PRECISION NOT NULL CHECK (center_lon BETWEEN -180 AND 180),
    radius_meters        INTEGER          NOT NULL CHECK (radius_meters > 0),
    capacity             INTEGER          NOT NULL CHECK (capacity >= 0),
    parking_restrictions JSONB            NOT NULL DEFAULT '[]'::jsonb,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT now()
);
