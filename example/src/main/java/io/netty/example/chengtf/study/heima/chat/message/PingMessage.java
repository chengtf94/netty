package io.netty.example.chengtf.study.heima.chat.message;


import io.netty.example.chengtf.study.heima.common.protocol.Message;

public class PingMessage extends Message {
    @Override
    public int getMessageType() {
        return PingMessage;
    }
}
