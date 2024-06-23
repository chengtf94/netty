package io.netty.example.chengtf.study.heima.chat.message;


import io.netty.example.chengtf.study.heima.common.message.Message;

public class PongMessage extends Message {
    @Override
    public int getMessageType() {
        return PongMessage;
    }
}
