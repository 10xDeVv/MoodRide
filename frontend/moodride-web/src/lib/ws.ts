import { Client, IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { JobSocketEvent } from "@/lib/types";

const configuredWsBaseUrl = process.env.NEXT_PUBLIC_WS_BASE_URL ?? "https://usewayward.app/ws";

function normalizeSockJsUrl(rawUrl: string): string {
  if (rawUrl.startsWith("wss://")) {
    return `https://${rawUrl.slice("wss://".length)}`;
  }
  if (rawUrl.startsWith("ws://")) {
    return `http://${rawUrl.slice("ws://".length)}`;
  }
  return rawUrl;
}

function resolveSockJsUrl(): string {
  const normalized = normalizeSockJsUrl(configuredWsBaseUrl);
  if (typeof window === "undefined") {
    return normalized;
  }

  try {
    const configured = new URL(normalized);
    const currentHost = window.location.hostname;
    const configuredHost = configured.hostname;
    const isWaywardHost = currentHost === "usewayward.app" || currentHost === "www.usewayward.app";
    const isConfiguredWaywardHost = configuredHost === "usewayward.app" || configuredHost === "www.usewayward.app";
    if (configured.origin === window.location.origin || (isWaywardHost && isConfiguredWaywardHost)) {
      return `${window.location.origin}/ws`;
    }
  } catch {
    // Keep the configured value if it is not a full URL.
  }

  return normalized;
}

function normalizeWsChannel(wsChannel: string, jobId: string): string {
  if (wsChannel.startsWith("/topic/")) {
    return wsChannel;
  }
  if (wsChannel.startsWith("job:")) {
    return `/topic/job/${jobId}`;
  }
  return `/topic/job/${jobId}`;
}

export function connectJobChannel(
  jobId: string,
  wsChannel: string,
  onEvent: (event: JobSocketEvent) => void,
  onError: (message: string) => void
): () => void {
  const destination = normalizeWsChannel(wsChannel, jobId);

  const client = new Client({
    reconnectDelay: 2000,
    webSocketFactory: () => new SockJS(resolveSockJsUrl())
  });

  client.onStompError = (frame) => {
    onError(frame.headers["message"] ?? "STOMP channel error.");
  };

  client.onWebSocketError = () => {
    onError("WebSocket transport error. Polling fallback remains active.");
  };

  client.onConnect = () => {
    client.subscribe(destination, (message: IMessage) => {
      try {
        const payload = JSON.parse(message.body) as JobSocketEvent;
        onEvent(payload);
      } catch {
        onError("Received non-JSON websocket payload.");
      }
    });
  };

  client.activate();

  return () => {
    client.deactivate();
  };
}
