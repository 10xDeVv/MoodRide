import { Client, IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { JobSocketEvent } from "@/lib/types";

const wsBaseUrl = process.env.NEXT_PUBLIC_WS_BASE_URL ?? "https://usewayward.app/ws";

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
    webSocketFactory: () => new SockJS(wsBaseUrl)
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
