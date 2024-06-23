package io.netty.example.chengtf.study.heima.chat.client.message;


import io.netty.example.chengtf.study.heima._0common.protocol.Message;

public class PingMessage extends Message {
    @Override
    public int getMessageType() {
        return PingMessage;
    }
}
