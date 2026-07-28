# Supabase PostgreSQL Integration Guide

This guide explains how to connect **automated-code-review-tool** to **Supabase** PostgreSQL as the primary production database when deployed on Render or running locally.

---

## 1. Create a Supabase Project

1. Go to [https://supabase.com](https://supabase.com) and sign in.
2. Click **New Project**, select an organization, name your project (e.g., `automated-code-review-tool`), set a secure **Database Password**, and choose your region (e.g., `US East / Oregon` to match your Render region).
3. Wait for the project database to finish provisioning (~1–2 minutes).

---

## 2. Obtain Your Supabase Database Credentials

1. Navigate to **Project Settings** → **Database** in the Supabase Dashboard.
2. Scroll to **Connection string** and select **JDBC**.
3. You will see connection options:

### Option A: Direct Connection (Recommended for low connection counts & Flyway migrations)
```
jdbc:postgresql://db.<PROJECT_REF>.supabase.co:5432/postgres?sslmode=require
```
- **Username:** `postgres`
- **Password:** `YOUR_SUPABASE_DB_PASSWORD`

### Option B: Connection Pooler / PgBouncer (Recommended for high concurrency on Render)
- **Session Pooler (Port 5432):**
  ```
  jdbc:postgresql://<POOLER_HOST>:5432/postgres?sslmode=require
  ```
- **Transaction Pooler (Port 6543):**
  ```
  jdbc:postgresql://<POOLER_HOST>:6543/postgres?sslmode=require&prepareThreshold=0
  ```
- **Username:** `postgres.<PROJECT_REF>`
- **Password:** `YOUR_SUPABASE_DB_PASSWORD`

> [!NOTE]
> When using the Transaction Pooler (port `6543`), always append `&prepareThreshold=0` to the JDBC URL so Spring Boot / Hibernate does not fail on prepared statements across pooled connections.

---

## 3. Configure Environment Variables on Render

1. Log in to your [Render Dashboard](https://dashboard.render.com).
2. Select your `automated-code-review-tool-api` Web Service.
3. Navigate to **Environment** settings and set/override the following environment variables:

| Environment Variable | Value |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db.<PROJECT_REF>.supabase.co:5432/postgres?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | `postgres` (or `postgres.<PROJECT_REF>` for pooler) |
| `SPRING_DATASOURCE_PASSWORD` | `YOUR_SUPABASE_DB_PASSWORD` |
| `SUPABASE_URL` *(Optional)* | `https://<PROJECT_REF>.supabase.co` |
| `SUPABASE_ANON_KEY` *(Optional)* | `YOUR_SUPABASE_ANON_KEY` |

4. Click **Save Changes**. Render will automatically trigger a new deployment of `automated-code-review-tool-api`.

---

## 4. Automatic Database Schema Initialization

The Spring Boot backend includes **Flyway** migration scripts (`V1__initial_schema.sql` .. `V4__schema_fixes.sql`).
Upon startup, the API service connects to your Supabase PostgreSQL instance, verifies Flyway migrations, and automatically creates all required tables:
- `users`
- `repositories`
- `pull_requests`
- `findings`
- `quality_metrics`
- `api_keys`
- `processed_webhooks`
- `anti_patterns`

---

## 5. Verifying the Connection

Check your Render `automated-code-review-tool-api` deployment logs. You should see logs confirming successful Flyway migration and HikariCP connection:
```text
c.a.AutomatedCodeReviewToolApplication : Starting AutomatedCodeReviewToolApplication...
o.f.c.i.database.base.BaseDatabaseType : Database: jdbc:postgresql://db.<PROJECT_REF>.supabase.co:5432/postgres (PostgreSQL 16.x)
o.f.core.internal.command.DbMigrate    : Current version of schema "public": 4
o.f.core.internal.command.DbMigrate    : Schema "public" is up to date. No migration necessary.
com.zaxxer.hikari.HikariDataSource     : HikariPool-1 - Start completed.
```

In your Supabase Dashboard under **Table Editor**, you will see all `automated-code-review-tool` tables populated and ready!
