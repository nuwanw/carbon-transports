/*
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.transport.http.netty.listener;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.CarbonMessageProcessor;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.common.Util;
import org.wso2.carbon.transport.http.netty.config.ListenerConfiguration;
import org.wso2.carbon.transport.http.netty.internal.HTTPTransportContextHolder;
import org.wso2.carbon.transport.http.netty.internal.websocket.BasicWebSocketChannelContextImpl;
import org.wso2.carbon.transport.http.netty.internal.websocket.WebSocketUtil;
import org.wso2.carbon.transport.http.netty.internal.websocket.message.WebSocketInitMessageImpl;
import org.wso2.carbon.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.carbon.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Class responsible for handle  incoming message through netty inbound pipeline.
 */
public class SourceHandler extends ChannelInboundHandlerAdapter {
    private static Logger log = LoggerFactory.getLogger(SourceHandler.class);

    protected ChannelHandlerContext ctx;
    private HTTPCarbonMessage cMsg;
    protected ConnectionManager connectionManager;
    protected Map<String, GenericObjectPool> targetChannelPool = new ConcurrentHashMap<>();
    protected ListenerConfiguration listenerConfiguration;

    public ListenerConfiguration getListenerConfiguration() {
        return listenerConfiguration;
    }

    public SourceHandler(ConnectionManager connectionManager, ListenerConfiguration listenerConfiguration)
            throws Exception {
        this.listenerConfiguration = listenerConfiguration;
        this.connectionManager = connectionManager;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.ctx = ctx;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        // Start the server connection Timer
        if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {
            HTTPTransportContextHolder.getInstance().getHandlerExecutor()
                    .executeAtSourceConnectionInitiation(Integer.toString(ctx.hashCode()));
        }
        this.ctx = ctx;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpMessage) {
            FullHttpMessage fullHttpMessage = (FullHttpMessage) msg;
            cMsg = (HTTPCarbonMessage) setupCarbonMessage(fullHttpMessage);
            publishToMessageProcessor(cMsg);
            ByteBuf content = ((FullHttpMessage) msg).content();
            cMsg.addHttpContent(new DefaultLastHttpContent(content));
            cMsg.setEndOfMsgAdded(true);
            if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {
                HTTPTransportContextHolder.getInstance().getHandlerExecutor().executeAtSourceRequestSending(cMsg);
            }

        } else if (msg instanceof HttpRequest) {

            // TODO: Change the default behavior to support WebSocket on demand.
            /*
            Checks whether the given connection is a WebSocketUpgrade and add necessary components to it.
             */
            HttpRequest httpRequest = (HttpRequest) msg;
            HttpHeaders headers = httpRequest.headers();
            if (isConnectionUpgrade(headers) &&
                    Constants.WEBSOCKET_UPGRADE.equalsIgnoreCase(headers.get(Constants.UPGRADE))) {
                log.info("Upgrading the connection from Http to WebSocket for " +
                                     "channel : " + ctx.channel());
                handleWebSocketHandshake(httpRequest);

            } else {
                cMsg = (HTTPCarbonMessage) setupCarbonMessage(httpRequest);
                publishToMessageProcessor(cMsg);
            }
            //Publish message to CarbonMessageProcessor
        } else {
            if (cMsg != null) {
                if (msg instanceof HttpContent) {
                    HttpContent httpContent = (HttpContent) msg;
                    cMsg.addHttpContent(httpContent);
                    if (msg instanceof LastHttpContent) {
                        cMsg.setEndOfMsgAdded(true);
                        if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {

                            HTTPTransportContextHolder.getInstance().getHandlerExecutor().
                                    executeAtSourceRequestSending(cMsg);
                        }

                    }
                }
            }
        }

    }

    /**
     * Some clients can send multiple parameters for "Connection" header. This checks whether the "Connection" header
     * contains "Upgrade" value.
     *
     * @param headers {@link HttpHeaders} of the request.
     * @return true if the "Connection" header contains value "Upgrade".
     */
    protected boolean isConnectionUpgrade(HttpHeaders headers) {
        if (!headers.contains(Constants.CONNECTION)) {
            return false;
        }

        String connectionHeaderValues = headers.get(Constants.CONNECTION);
        for (String connectionValue: connectionHeaderValues.split(",")) {
            if (Constants.UPGRADE.equalsIgnoreCase(connectionValue.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle the WebSocket handshake.
     *
     * @param httpRequest {@link HttpRequest} of the request.
     */
    protected void handleWebSocketHandshake(HttpRequest httpRequest) {
            boolean isSecured = false;
            if (listenerConfiguration.getSslConfig() != null) {
                isSecured = true;
            }
            String uri = httpRequest.uri();
            String subProtocol = WebSocketUtil.getSubProtocol(httpRequest);
            boolean isServerMessage = true;

            BasicWebSocketChannelContextImpl webSocketChannelContext = new BasicWebSocketChannelContextImpl(
                    subProtocol, uri, listenerConfiguration.getId(), isSecured, isServerMessage, connectionManager,
                    listenerConfiguration);

            WebSocketInitMessageImpl initMessage =
                    new WebSocketInitMessageImpl(httpRequest, webSocketChannelContext,ctx);

            // TODO: Publish Message
    }

    //Carbon Message is published to registered message processor and Message Processor should return transport thread
    //immediately
    protected void publishToMessageProcessor(CarbonMessage cMsg) throws URISyntaxException {
        if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {
            HTTPTransportContextHolder.getInstance().getHandlerExecutor().executeAtSourceRequestReceiving(cMsg);
        }

        boolean continueRequest = true;

        if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {

            continueRequest = HTTPTransportContextHolder.getInstance().getHandlerExecutor()
                    .executeRequestContinuationValidator(cMsg, carbonMessage -> {
                        CarbonCallback responseCallback = (CarbonCallback) cMsg
                                .getProperty(org.wso2.carbon.messaging.Constants.CALL_BACK);
                        responseCallback.done(carbonMessage);
                    });

        }
        if (continueRequest) {
            CarbonMessageProcessor carbonMessageProcessor = HTTPTransportContextHolder.getInstance()
                        .getMessageProcessor(listenerConfiguration.getMessageProcessorId());
            if (carbonMessageProcessor != null) {
                try {
                    carbonMessageProcessor.receive(cMsg, new ResponseCallback(this.ctx, cMsg));
                } catch (Exception e) {
                    log.error("Error while submitting CarbonMessage to CarbonMessageProcessor", e);
                }
            } else {
                log.error("Cannot find registered MessageProcessor for forward the message");
            }
        }

    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Stop the connector timer
        ctx.close();
        if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {
            HTTPTransportContextHolder.getInstance().getHandlerExecutor()
                    .executeAtSourceConnectionTermination(Integer.toString(ctx.hashCode()));
        }

        targetChannelPool.forEach((k, genericObjectPool) -> {
            try {
                targetChannelPool.remove(k).close();
            } catch (Exception e) {
                log.error("Couldn't close target channel socket connections", e);
            }
        });

        connectionManager.notifyChannelInactive();
    }

    public Map<String, GenericObjectPool> getTargetChannelPool() {
        return targetChannelPool;
    }

    public ChannelHandlerContext getInboundChannelContext() {
        return ctx;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected CarbonMessage setupCarbonMessage(HttpMessage httpMessage) throws URISyntaxException {
        cMsg = new HTTPCarbonMessage();
        boolean isSecuredConnection = false;
        if (HTTPTransportContextHolder.getInstance().getHandlerExecutor() != null) {
            HTTPTransportContextHolder.getInstance().getHandlerExecutor().executeAtSourceRequestReceiving(cMsg);
        }

        HttpRequest httpRequest = (HttpRequest) httpMessage;
        cMsg.setProperty(Constants.MESSAGE_PROCESSOR_ID, listenerConfiguration.getMessageProcessorId());
        cMsg.setProperty(Constants.CHNL_HNDLR_CTX, this.ctx);
        cMsg.setProperty(Constants.SRC_HANDLER, this);
        cMsg.setProperty(Constants.HTTP_VERSION, httpRequest.getProtocolVersion().text());
        cMsg.setProperty(Constants.HTTP_METHOD, httpRequest.getMethod().name());
        cMsg.setProperty(org.wso2.carbon.messaging.Constants.LISTENER_PORT,
                ((InetSocketAddress) ctx.channel().localAddress()).getPort());
        cMsg.setProperty(org.wso2.carbon.messaging.Constants.LISTENER_INTERFACE_ID, listenerConfiguration.getId());
        cMsg.setProperty(org.wso2.carbon.messaging.Constants.PROTOCOL, Constants.PROTOCOL_NAME);
        if (listenerConfiguration.getSslConfig() != null) {
            isSecuredConnection = true;
        }
        cMsg.setProperty(Constants.IS_SECURED_CONNECTION, isSecuredConnection);
        cMsg.setProperty(Constants.LOCAL_ADDRESS, ctx.channel().localAddress());

        cMsg.setProperty(Constants.REQUEST_URL, httpRequest.getUri());
        ChannelHandler handler = ctx.handler();
        cMsg.setProperty(Constants.CHANNEL_ID, ((SourceHandler) handler).getListenerConfiguration().getId());
        cMsg.setProperty(Constants.TO, httpRequest.getUri());
        cMsg.setHeaders(Util.getHeaders(httpRequest).getAll());
        //Added protocol name as a string

        return cMsg;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.close();
        }
    }
}
