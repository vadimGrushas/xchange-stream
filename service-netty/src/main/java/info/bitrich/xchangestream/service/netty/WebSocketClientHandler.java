package info.bitrich.xchangestream.service.netty;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.CharsetUtil;

public class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketClientHandler.class);

    @FunctionalInterface
    public interface WebSocketTextMessageHandler {
        void onMessage(String message);
    }

    @FunctionalInterface
    public interface WebSocketBinaryMessageHandler {
        void onMessage(ByteBuf message);
    }

    private final WebSocketClientHandshaker handshaker;
    private final WebSocketTextMessageHandler textMessagehandler;
    private final WebSocketBinaryMessageHandler binaryMessagehandler;
    private ChannelPromise handshakeFuture;

    public WebSocketClientHandler(WebSocketClientHandshaker handshaker,
                                  WebSocketTextMessageHandler textMessagehandler,
                                  WebSocketBinaryMessageHandler binaryMessagehandler) {
        this.handshaker = handshaker;
        this.textMessagehandler = textMessagehandler;
        this.binaryMessagehandler = binaryMessagehandler;
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        handshakeFuture = ctx.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOG.info("WebSocket Client disconnected!");
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ch, (FullHttpResponse)msg);
            LOG.info("WebSocket Client connected!");
            handshakeFuture.setSuccess();
            }
            catch (WebSocketHandshakeException e) {
                LOG.error("WebSocket Client failed to connect. {}", e.getMessage());
                handshakeFuture.setFailure(e);
            }
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse)msg;
            throw new IllegalStateException("Unexpected FullHttpResponse (getStatus=" + response.status() + ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
        }

        WebSocketFrame frame = (WebSocketFrame)msg;
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame)frame;
            textMessagehandler.onMessage(textFrame.text());
        } else if (frame instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame)frame;
            binaryMessagehandler.onMessage(binaryFrame.content());
        } else if (frame instanceof PingWebSocketFrame) {
            LOG.debug("WebSocket Client received ping");
            ch.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof PongWebSocketFrame) {
            LOG.trace("WebSocket Client received pong");
        } else if (frame instanceof CloseWebSocketFrame) {
            LOG.info("WebSocket Client received closing");
            ch.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.debug("", cause);
        if (!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(cause);
        }
        ctx.close();
    }
}