"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import SockJS from "sockjs-client";
import { Stomp } from "@stomp/stompjs";
import type { CompatClient } from "@stomp/stompjs";
import type { ReactNode } from "react";

const WebSocketContext = createContext<CompatClient | undefined>(undefined);

export function useWebSocket(): CompatClient | undefined {
  return useContext(WebSocketContext);
}

type WebSocketProviderProps = {
  children: ReactNode;
};

export default function SocketProvider({ children }: WebSocketProviderProps) {
  const [stompClient, setStompClient] = useState<CompatClient | undefined>(undefined);

  useEffect(() => {
    const accessToken = localStorage.getItem("accessToken");
    const token = `Bearer ${accessToken}`;
    const socketFactory = () => new SockJS(process.env.NEXT_PUBLIC_SERVER_URL + "quiz/stomp/ws");
    const client = Stomp.over(socketFactory);

    function connect() {
      // const headers = { Authorization: `Bearer ${accessToken}` };
      // console.log(`Headers: ${JSON.stringify(headers)}`); // 헤더가 올바르게 설정되었는지 확인

      client.connect(
        {
          Authorization: token,
        },
        function connection() {
          setStompClient(client);
        }
      );
    }

    connect();

    return () => {
      if (client) {
        client.disconnect();
      }
    };
  }, []);

  return <WebSocketContext.Provider value={stompClient}>{children}</WebSocketContext.Provider>;
}
