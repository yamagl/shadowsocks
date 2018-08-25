package com.zkdcloud.shadowsocks.client.socks5.channelHandler.inbound;

import com.zkdcloud.shadowsocks.client.socks5.context.ClientContextConstant;
import com.zkdcloud.shadowsocks.client.socks5.context.RepType;
import com.zkdcloud.shadowsocks.common.bean.ClientConfig;
import com.zkdcloud.shadowsocks.common.cipher.Aes128CfbCipher;
import com.zkdcloud.shadowsocks.common.context.ContextConstant;
import com.zkdcloud.shadowsocks.common.util.ShadowsocksConfigUtil;
import com.zkdcloud.shadowsocks.common.util.SocksIpUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.net.util.IPAddressUtil;

import java.net.InetSocketAddress;

public class Socks5ConnectOperatorInbound extends SimpleChannelInboundHandler<ByteBuf> {
    /**
     * static logger
     */
    private static Logger logger = LoggerFactory.getLogger(Socks5ConnectOperatorInbound.class);

    /**
     * remote connection
     */
    private Channel proxyChannel;
    /**
     * clientChannel
     */
    private Channel clientChannel;
    /**
     * remote bootstrap
     */
    private Bootstrap remoteBootstrap;
    private Aes128CfbCipher decodeCipher;
    /**
     * one thread eventLoopGroup
     */
    public static NioEventLoopGroup singleEventLoopGroup = new NioEventLoopGroup(10, new DefaultThreadFactory("singleEventLoopGroup"));

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        if (proxyChannel == null) {
            buildConnect(ctx);
        }

        if (msg != null && msg.isReadable()) {
            proxyChannel.writeAndFlush(getCryptoMessage(msg));
        }
    }

    /**
     * build remote connection
     *
     * @param ctx ctx
     */
    private void buildConnect(ChannelHandlerContext ctx){
        remoteBootstrap = new Bootstrap();
        clientChannel = ctx.channel();

        remoteBootstrap.group(singleEventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(0, 0, 3))
                                .addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                                        clientChannel.writeAndFlush(msg.retain());
                                    }

                                    @Override
                                    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                        closeChannel();
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                        logger.error("channelId: {}, cause : {}", ctx.channel().id(), cause.getMessage());
                                        closeChannel();
                                    }
                                });
                    }
                });

        InetSocketAddress proxyAddress = getProxyAddress();
        remoteBootstrap.connect(proxyAddress).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                proxyChannel = future.channel();
                //send the connection is build
                sendAcc();

                if (logger.isDebugEnabled()) {
                    logger.debug("remote channel is connected");
                }
            } else {
                logger.error("channelId: {}, cause : {}", future.channel().id(), future.cause().getMessage());
                closeChannel();
            }
        });
    }

    /**
     * close channel
     */
    private void closeChannel() {
        if (proxyChannel != null) {
            proxyChannel.close();
            proxyChannel = null;
        }

        if (clientChannel != null) {
            clientChannel.close();
            clientChannel = null;
        }

        if (remoteBootstrap != null) {
            remoteBootstrap = null;
        }
    }

    /**
     * send acc connection result
     */
    private void sendAcc() {
        ByteBuf repMessage = clientChannel.alloc().heapBuffer();
        repMessage.writeByte(ClientContextConstant.SOCKS5_VERSION);//version
        repMessage.writeByte(RepType.OPERATOR_SUCCESS.getValue()); //ok
        repMessage.writeByte(0x00);//rsv

        repMessage.writeByte(0x01);// ip type
        repMessage.writeInt(SocksIpUtils.ip4ToInt(((InetSocketAddress) proxyChannel.remoteAddress()).getHostName()));// ipv4 addr
        repMessage.writeShort(3030);// ip port

        clientChannel.writeAndFlush(repMessage);
    }

    /**
     * get proxyAddress from 'config.json'
     *
     * @return inetSocketAddress
     */
    private InetSocketAddress getProxyAddress() {
        ClientConfig clientConfig = ShadowsocksConfigUtil.getClientConfigInstance();
        clientChannel.attr(ClientContextConstant.CLIENT_CONFIG).setIfAbsent(clientConfig);
        clientChannel.attr(ContextConstant.AES_128_CFB_KEY).setIfAbsent(new Aes128CfbCipher(clientConfig.getPassword()));

        return new InetSocketAddress(clientConfig.getServer(), clientConfig.getServer_port());
    }

    /**
     * 获取加密消息
     *
     * @param message message
     * @return byteBuf
     */
    private ByteBuf getCryptoMessage(ByteBuf message) {
        try {
            ByteBuf willEncodeMessage = clientChannel.alloc().heapBuffer();

            boolean isFirstEncoding = clientChannel.attr(ClientContextConstant.FIRST_ENCODING).get() == null || !clientChannel.attr(ClientContextConstant.FIRST_ENCODING).get();
            if (isFirstEncoding) {
                //get queryAddress
                InetSocketAddress queryAddress = clientChannel.attr(ClientContextConstant.QUERY_ADDRESS).get();
                if (queryAddress == null) {
                    closeChannel();
                    throw new IllegalArgumentException("no remote address");
                }

                String queryHost = queryAddress.getHostName();
                if (IPAddressUtil.isIPv4LiteralAddress(queryHost)) {
                    willEncodeMessage.writeByte(0x01); //ipv4
                    willEncodeMessage.writeBytes(IPAddressUtil.textToNumericFormatV4(queryHost));
                    willEncodeMessage.writeShort(queryAddress.getPort());
                } else if (IPAddressUtil.isIPv6LiteralAddress(queryHost)) {
                    willEncodeMessage.writeByte(0x04);//ipv6
                    willEncodeMessage.writeBytes(IPAddressUtil.textToNumericFormatV6(queryHost));
                    willEncodeMessage.writeShort(queryAddress.getPort());
                } else {
                    willEncodeMessage.writeByte(0x03);//domain type
                    byte[] asciiHost = AsciiString.of(queryHost).array();
                    willEncodeMessage.writeByte(asciiHost.length);//domain type
                    willEncodeMessage.writeBytes(asciiHost);
                    willEncodeMessage.writeShort(queryAddress.getPort());
                }
            }

            byte[] payload = new byte[message.readableBytes()];
            message.readBytes(payload);
            willEncodeMessage.writeBytes(payload);

            return cryptoMessage(willEncodeMessage);
        } finally {
            clientChannel.attr(ClientContextConstant.FIRST_ENCODING).set(false);
        }
    }

    /**
     * cryptoMessage
     *
     * @param willEncodeMessage willEncoding Message
     * @return after entry message
     */
    private ByteBuf cryptoMessage(ByteBuf willEncodeMessage) {
        try {
            ByteBuf result = clientChannel.alloc().heapBuffer();

            Aes128CfbCipher cipher = clientChannel.attr(ContextConstant.AES_128_CFB_KEY).get();
            byte[] originBytes = new byte[willEncodeMessage.readableBytes()];
            willEncodeMessage.readBytes(originBytes);

            //entry
            byte[] secretBytes = cipher.encodeBytes(originBytes);
//            testEntry(originBytes,secretBytes);

            result.writeBytes(secretBytes);
            return result;
        } finally {
            ReferenceCountUtil.release(willEncodeMessage);
        }
    }

    private void testEntry(byte[] originBytes, byte[] secretBytes) {
        System.out.println("origin:");
        printBytes(originBytes);
        if(decodeCipher == null){
            decodeCipher = new Aes128CfbCipher("123456");
        }

        System.out.println("after decode :");
        printBytes(decodeCipher.decodeBytes(Unpooled.wrappedBuffer(secretBytes)));
    }

    private void printBytes(byte[] data){
        for (int i = 0; i < data.length; i++) {
            System.out.print(data[i] + ",");
        }
        System.out.println();
    }
}
