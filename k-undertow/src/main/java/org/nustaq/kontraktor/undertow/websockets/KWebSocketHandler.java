package org.nustaq.kontraktor.undertow.websockets;

import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.nustaq.kontraktor.remoting.http.websocket.WebSocketEndpoint;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by ruedi on 27/03/15.
 */
public class KWebSocketHandler extends WebSocketProtocolHandshakeHandler {

    WebSocketEndpoint endpoint;

    public static WebSocketConnectionCallback Connect(WebSocketEndpoint endpoint) {
        KWebSocketHandler handler[] = {null};
        WebSocketConnectionCallback cb = (ex, ch) -> {
            handler[0].doConnect(ex, ch);
        };
        handler[0] = new KWebSocketHandler(endpoint, cb);
        return cb;
    }

    protected KWebSocketHandler(WebSocketEndpoint endpoint, WebSocketConnectionCallback cb ) {
        super(cb);
        this.endpoint = endpoint;
    }

    public void doConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        UndertowChannel utChannel = new UndertowChannel(channel);
        channel.setAttribute("utchannel",utChannel);

        endpoint.onOpen(utChannel);
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) throws IOException {
                endpoint.onTextMessage( utChannel, message.getData() );
            }

            @Override
            protected void onFullBinaryMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                ByteBuffer[] data = message.getData().getResource();
                endpoint.onBinaryMessage( utChannel, getBytes(data) );
            }

            @Override
            protected void onFullPingMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                System.out.println("PING");
                super.onFullPingMessage(channel, message);
            }

            @Override
            protected void onFullPongMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                System.out.println("PONG");
                endpoint.onPong( utChannel );
            }

            @Override
            protected void onFullCloseMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                super.onFullCloseMessage(channel,message);
            }

            @Override
            protected void onCloseMessage(CloseMessage cm, WebSocketChannel channel) {
                endpoint.onClose( utChannel );
            }

            @Override
            protected void onError(WebSocketChannel channel, Throwable error) {
                endpoint.onError( utChannel );
            }
        });
        channel.resumeReceives();
    }

    private byte[] getBytes(ByteBuffer[] data) {
        int len = 0;
        for (int i = 0; i < data.length; i++) {
            ByteBuffer byteBuffer = data[i];
            len += byteBuffer.limit() - byteBuffer.arrayOffset();
        }
        byte res[] = new byte[len];
        int off = 0;
        for (int i = 0; i < data.length; i++) {
            ByteBuffer byteBuffer = data[i];
            byteBuffer.get( res, off, byteBuffer.limit() - byteBuffer.arrayOffset() );
        }
        return res;
    }

}
