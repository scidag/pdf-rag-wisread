# Wisread MVP M6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the login/register page match the cloned prototype and finish MVP acceptance documentation, deployment docs, and project README.

**Architecture:** Port the prototype's dark animated canvas + glass login card into the Next.js frontend, then document and verify the MVP acceptance criteria.

**Tech Stack:** Next.js 15, React 19, Tailwind CSS, Playwright, Docker Compose.

---

## Task 1: Port Prototype Auth Screen

**Files:**
- Create: `frontend/components/AuthCanvas.tsx`
- Create: `frontend/components/AuthScreen.tsx`
- Modify: `frontend/app/login/page.tsx`
- Modify: `frontend/app/register/page.tsx`

- [ ] **Step 1: Port animated canvas**

Recreate the prototype's dark radial background, bezier curves, moving dots, and center glow.

- [ ] **Step 2: Port glass login card**

Sign In / Sign Up tabs, email/password/name fields, show password toggle, loading state, footer links, dark glass style.

- [ ] **Step 3: Wire real auth**

Keep calling `/auth/login` and `/auth/register`; show API errors inside the card.

## Task 2: Playwright Re-verify

**Files:**
- Modify: `backend/target/verify_frontend.py`

- [ ] **Step 1: Update selectors**

Use placeholders and tab buttons from the new prototype-based page.

- [ ] **Step 2: Verify desktop and mobile**

Login, create conversation, ask question, check `[1]` source card and mobile overflow.

## Task 3: Acceptance Documentation

**Files:**
- Create: `docs/acceptance/mvp-acceptance.md`

- [ ] **Step 1: Define 20 evaluation questions**

Five PDF categories with four questions each, plus scoring rules for correctness, citation accuracy, and hallucination.

- [ ] **Step 2: Document manual and automated test matrix**

Upload, status, chat, citations, security isolation, and performance targets.

## Task 4: README and Deployment Docs

**Files:**
- Create: `README.md`
- Create: `docs/deployment.md`

- [ ] **Step 1: Write project README**

Features, architecture, local startup, API overview, tech stack, screenshots placeholders.

- [ ] **Step 2: Write deployment guide**

Docker Compose services, environment variables, Flyway, MinIO, production notes, DashScope key setup.

## Task 5: Final Verification

Run:
```bash
cd backend && mvn test
cd frontend && npm run build
python backend/target/verify_frontend.py
```

Expected: all tests pass, build passes, Playwright flow passes on desktop and mobile.
