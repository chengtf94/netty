package io.netty.example.chengtf.study.heima.chatroom.message;


import io.netty.example.chengtf.study.heima.common.protocol.Message;

public class PongMessage extends Message {
    @Override
    public int getMessageType() {
        return PongMessage;
    }
}
