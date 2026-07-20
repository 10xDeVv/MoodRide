import { NextRequest, NextResponse } from "next/server";

const UPSTREAM = process.env.UPSTREAM_API_BASE ?? "https://usewayward.app";
const UPSTREAM_REQUEST_TIMEOUT_MS = 20_000;

async function handler(req: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  const upstreamUrl = `${UPSTREAM}/${path.join("/")}${req.nextUrl.search}`;

  const headers = new Headers();
  headers.set("Content-Type", req.headers.get("Content-Type") ?? "application/json");
  const auth = req.headers.get("Authorization");
  if (auth) headers.set("Authorization", auth);

  const init: RequestInit = {
    method: req.method,
    headers,
    cache: "no-store",
  };

  if (req.method !== "GET" && req.method !== "HEAD") {
    const body = await req.text();
    if (body) init.body = body;
  }

  const controller = new AbortController();
  let upstreamTimedOut = false;
  const onRequestAbort = () => {
    controller.abort(req.signal.reason);
  };

  if (req.signal.aborted) {
    onRequestAbort();
  } else {
    req.signal.addEventListener("abort", onRequestAbort, { once: true });
  }

  const deadlineTimer = setTimeout(() => {
    if (!controller.signal.aborted) {
      upstreamTimedOut = true;
      controller.abort(new DOMException("Upstream request timed out", "TimeoutError"));
    }
  }, UPSTREAM_REQUEST_TIMEOUT_MS);
  init.signal = controller.signal;

  try {
    const upstream = await fetch(upstreamUrl, init);
    const responseBody = await upstream.text();

    return new NextResponse(responseBody, {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("Content-Type") ?? "application/json",
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    if (upstreamTimedOut) {
      return NextResponse.json(
        { error: "Upstream request timed out." },
        { status: 504, headers: { "Cache-Control": "no-store" } }
      );
    }
    if (req.signal.aborted) {
      return new NextResponse(null, {
        status: 499,
        headers: { "Cache-Control": "no-store" },
      });
    }

    const msg = err instanceof Error ? err.message : "Proxy error";
    return NextResponse.json(
      { error: msg },
      { status: 502, headers: { "Cache-Control": "no-store" } }
    );
  } finally {
    clearTimeout(deadlineTimer);
    req.signal.removeEventListener("abort", onRequestAbort);
  }
}

export const GET = handler;
export const HEAD = handler;
export const POST = handler;
export const PUT = handler;
export const PATCH = handler;
export const DELETE = handler;
