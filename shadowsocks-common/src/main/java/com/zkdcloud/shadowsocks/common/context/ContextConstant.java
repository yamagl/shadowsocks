package com.zkdcloud.shadowsocks.common.context;

import com.zkdcloud.shadowsocks.common.cipher.Aes128CfbCipher;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;

/**
 * description
 *
 * @author zk
 * @since 2018/8/11
 */
public class ContextConstant {
    /**
     * 128 ctb key
     */
    public static AttributeKey<Aes128CfbCipher> AES_128_CFB_KEY = AttributeKey.valueOf("aes128");
}