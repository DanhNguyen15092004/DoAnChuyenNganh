-- Binary comparison rejects case variants and trailing spaces regardless of DB collation.
-- Keep the already exercised V2 unchanged; tighten the constraint with a new migration.
ALTER TABLE app_users
    DROP CHECK ck_app_users_role,
    ADD CONSTRAINT ck_app_users_role_exact
        CHECK (CAST(role AS BINARY) IN ('STUDENT', 'TEACHER', 'ADMIN'));
