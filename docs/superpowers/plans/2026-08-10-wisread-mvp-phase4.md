# Wisread MVP Phase 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Next.js frontend workbench: login/register, document upload and status, conversation list, SSE streaming chat, and citation source cards.

**Architecture:** Next.js App Router + React 19 + Tailwind CSS. The frontend keeps the JWT access token in memory, restores it via the refresh Cookie, and calls the FastAPI-free Spring Boot API at `http://localhost:8080/api/v1`.

**Tech Stack:** Next.js 15, React 19, TypeScript, Tailwind CSS 3, lucide-react, fetch + SSE parsing.

---

## Task 1: Backend CORS

**Files:**
- Create: `backend/src/main/java/com/wisread/config/CorsConfig.java`

- [ ] **Step 1: Allow localhost:3000**

Allow `http://localhost:3000`, credentials, and common HTTP methods/headers.

## Task 2: Frontend Skeleton

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/next.config.mjs`
- Create: `frontend/postcss.config.mjs`
- Create: `frontend/tailwind.config.ts`
- Create: `frontend/app/globals.css`
- Create: `frontend/app/layout.tsx`

- [ ] **Step 1: Scaffold package and configs**

Use Next.js 15, React 19, TypeScript, Tailwind 3.

- [ ] **Step 2: Install dependencies**

Run `npm install`.

## Task 3: API Client and Auth State

**Files:**
- Create: `frontend/lib/types.ts`
- Create: `frontend/lib/api.ts`
- Create: `frontend/lib/auth-store.ts`

- [ ] **Step 1: Define shared types**

`User`, `Document`, `Conversation`, `Message`, `Source`.

- [ ] **Step 2: Implement API client**

`apiFetch` attaches `Authorization`, refreshes once on 401, and parses JSON.

- [ ] **Step 3: Implement auth store**

In-memory access token, `login`, `register`, `logout`, and `restoreSession` via `/auth/refresh`.

## Task 4: Auth Pages

**Files:**
- Create: `frontend/components/AuthPanel.tsx`
- Create: `frontend/app/login/page.tsx`
- Create: `frontend/app/register/page.tsx`

- [ ] **Step 1: Build shared auth panel**

Username/email/password form, submit states, error messages, link to the other page.

- [ ] **Step 2: Wire login and register**

On success store access token and redirect to `/workspace`.

## Task 5: Workspace Layout and Documents

**Files:**
- Create: `frontend/app/workspace/page.tsx`
- Create: `frontend/components/Sidebar.tsx`
- Create: `frontend/components/DocumentList.tsx`
- Create: `frontend/components/UploadPanel.tsx`
- Create: `frontend/components/StatusBadge.tsx`

- [ ] **Step 1: Build two-column workspace**

Left sidebar: logout, upload, document list, conversation list. Right: chat panel.

- [ ] **Step 2: Upload and poll status**

Upload PDF, poll `/documents/{id}/status` until READY or FAILED, show status badges and delete buttons.

## Task 6: Chat UI with SSE

**Files:**
- Create: `frontend/components/ConversationList.tsx`
- Create: `frontend/components/ChatPanel.tsx`
- Create: `frontend/components/SourceCard.tsx`
- Create: `frontend/lib/sse.ts`

- [ ] **Step 1: Build conversation list**

Create conversation for selected document, switch between conversations.

- [ ] **Step 2: Implement SSE chat**

Parse `event:delta` and `event:done`, append assistant message, render source cards.

## Task 7: Verification

Run:
```bash
cd frontend
npm run build
npm run dev
```

Expected: build passes; `http://localhost:3000` opens login, register works, workspace uploads a PDF, chat streams and shows `[1]` source cards.
