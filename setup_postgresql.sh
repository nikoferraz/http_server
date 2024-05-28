#!/bin/bash

set -e

echo "Setting up PostgreSQL database for TechEmpower benchmarks..."

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-benchmarkdbuser}"
DB_PASSWORD="${DB_PASSWORD:-benchmarkdbpass}"
DB_NAME="${DB_NAME:-benchmarkdb}"

echo "Creating database and tables..."

psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1 || psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -c "CREATE DATABASE $DB_NAME"

psql -h "$DB_HOST" -p "$DB_PORT" -U postgres -d "$DB_NAME" <<EOF
CREATE USER IF NOT EXISTS $DB_USER WITH PASSWORD '$DB_PASSWORD';
ALTER ROLE $DB_USER WITH CREATEDB;
GRANT ALL PRIVILEGES ON DATABASE $DB_NAME TO $DB_USER;

DROP TABLE IF EXISTS Fortune;
DROP TABLE IF EXISTS World;

CREATE TABLE World (
  id integer NOT NULL PRIMARY KEY,
  randomNumber integer NOT NULL
);

CREATE TABLE Fortune (
  id integer NOT NULL PRIMARY KEY,
  message varchar(2048) NOT NULL
);

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $DB_USER;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO $DB_USER;
EOF

echo "Populating World table with 10,000 rows..."

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<'EOF'
INSERT INTO World (id, randomNumber) VALUES
EOF

# Generate INSERT statements for rows 1-10000 with random numbers
for i in {1..10000}; do
  RANDOM_NUM=$((RANDOM % 10000 + 1))
  if [ $i -eq 10000 ]; then
    echo "($i, $RANDOM_NUM);" >> /tmp/world_insert.sql
  else
    echo "($i, $RANDOM_NUM)," >> /tmp/world_insert.sql
  fi
done

# Execute inserts in batches
cat /tmp/world_insert.sql | psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" << 'EOF'
INSERT INTO World (id, randomNumber) VALUES
EOF

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f /tmp/world_insert.sql

echo "Populating Fortune table..."

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<'EOF'
INSERT INTO Fortune (id, message) VALUES
(1, 'fortune: No such file or directory'),
(2, 'A computer program does what you tell it to do, not what you want it to do.'),
(3, 'After enough decimal places, nobody gives a damn.'),
(4, 'A few years ago I had an experience that I've never forgotten.'),
(5, 'Any program that runs right is obsolete.'),
(6, 'A programming language is low level when its programs require attention to the irrelevant.'),
(7, 'A language that doesn''t effect the way you think about programming is not worth knowing.'),
(8, 'Simplicity does not precede complexity, but follows it.'),
(9, 'You can''t communicate complexity, only an awareness of it.'),
(10, 'Computers make very fast, very accurate mistakes.'),
(11, 'Civilization advances by extending the number of important operations which we can perform without thinking about them.'),
(12, 'Check the return codes of system calls.'),
(13, 'Thou shalt study thy libraries.'),
(14, 'The most important rule for the new language designer is that the new language shall be easy to write the text of his or her programs, but hard to read other people''s programs.'),
(15, 'Beware of the Turing tar-pit in which everything is possible but nothing of interest is easy.');
EOF

echo "Creating indexes..."

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<EOF
CREATE INDEX idx_world_id ON World(id);
CREATE INDEX idx_fortune_id ON Fortune(id);
EOF

rm -f /tmp/world_insert.sql

echo "PostgreSQL database setup complete!"
echo "Database: $DB_NAME"
echo "User: $DB_USER"
echo "Host: $DB_HOST:$DB_PORT"
