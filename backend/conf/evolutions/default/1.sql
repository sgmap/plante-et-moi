# --- !Ups
CREATE TABLE review (
    application_id character varying(100) NOT NULL,
    agent_id character varying(100) NOT NULL,
    creation_date date NOT NULL,
    favorable boolean NOT NULL,
    comment text NOT NULL,
    PRIMARY KEY (application_id, agent_id)
);

CREATE TABLE application_imported (
    id character varying(100) NOT NULL,
    city character varying(100) NOT NULL,
    firstname character varying(100) NOT NULL,
    lastname character varying(100) NOT NULL,
    email character varying(150) NOT NULL,
    type character varying(50) NOT NULL,
    address character varying(500) NOT NULL,
    creation_date date NOT NULL,
    coordinates point NOT NULL,
    source character varying(50) NOT NULL,
    source_id character varying(50) NOT NULL,
    phone character varying(50) NULL,
    fields json NOT NULL,
    files json NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE application_extra (
    application_id character varying(100) NOT NULL,
    status character varying(100) NOT NULL,
    PRIMARY KEY (application_id)
);

# --- !Downs
DROP TABLE application_extra;
DROP TABLE application_imported;
DROP TABLE review;
