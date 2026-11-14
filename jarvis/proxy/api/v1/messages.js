// Jarvis proxy as a Vercel Function. Deployed at  https://<project>.vercel.app/api/v1/messages
// In the Jarvis app set:  API base URL = https://<project>.vercel.app/api   and   Proxy token = <JARVIS_TOKENS entry>
//
// Environment variables (set them in Vercel → Project → Settings → Environment Variables):
//   ANTHROPIC_API_KEY   your Claude key (never commit it)
//   JARVIS_TOKENS       comma-separated long random tokens, one per phone
//   JARVIS_RPM          optional, requests per minute per token per instance (default 30)
//   JARVIS_MODELS       optional allow-list (default claude-sonnet-5-5,claude-opus-5-5,claude-haiku-5-5)

import { timingSafeEqual } from "node:crypto";

export const config = { maxDuration: 300 };

const hits = new Map(); // token -> timestamps (per warm instance; a best-effort brake, not a global limit)

function tokens() {
  return (process.env.JARVIS_TOKENS || "").split(",").map((t) => t.trim()).filter(Boolean);
}

function matchToken(header) {
  const m = /^Bearer (.+)$/.exec(header || "");
  if (!m) return null;
  const given = Buffer.from(m[1]);
  for (const t of tokens()) {
    const want = Buffer.from(t);
    if (given.length === want.length && timingSafeEqual(given, want)) return t;
  }
  return null;
}

function limited(token) {
  const rpm = Number(process.env.JARVIS_RPM || 30);
  const now = Date.now();
  const list = (hits.get(token) || []).filter((t) => now - t < 60_000);
  if (list.length >= rpm) return true;
  list.push(now);
  hits.set(token, list);
  return false;
}

function err(status, type, message) {
  return new Response(JSON.stringify({ type: "error", error: { type, message } }), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export async function POST(request) {
  if (!process.env.ANTHROPIC_API_KEY || tokens().length === 0) {
    return err(500, "api_error", "Proxy is not configured (ANTHROPIC_API_KEY / JARVIS_TOKENS)");
  }
  const token = matchToken(request.headers.get("authorization"));
  if (!token) return err(401, "authentication_error", "Bad proxy token");
  if (limited(token)) return err(429, "rate_limit_error", "Proxy rate limit");

  let body;
  try {
    body = await request.json();
  } catch {
    return err(400, "invalid_request_error", "Invalid JSON");
  }
  const allowed = (process.env.JARVIS_MODELS || "claude-sonnet-5-5,claude-opus-5-5,claude-haiku-5-5").split(",");
  if (!allowed.includes(body.model)) return err(400, "invalid_request_error", "Model not allowed by proxy");

  let upstream;
  try {
    upstream = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": process.env.ANTHROPIC_API_KEY,
        "anthropic-version": request.headers.get("anthropic-version") || "2026-10-21",
        ...(request.headers.get("anthropic-beta") ? { "anthropic-beta": request.headers.get("anthropic-beta") } : {}),
      },
      body: JSON.stringify(body),
      signal: request.signal, // phone disconnects (kill switch) -> upstream request is cancelled
    });
  } catch {
    return err(502, "api_error", "Upstream unreachable");
  }

  const headers = new Headers({ "content-type": upstream.headers.get("content-type") || "application/json" });
  const retry = upstream.headers.get("retry-after");
  if (retry) headers.set("retry-after", retry);
  headers.set("cache-control", "no-store");
  return new Response(upstream.body, { status: upstream.status, headers });
}

export function GET() {
  return err(405, "invalid_request_error", "Use POST");
}
