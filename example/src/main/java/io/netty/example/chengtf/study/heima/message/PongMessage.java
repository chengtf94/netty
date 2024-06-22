package io.netty.example.chengtf.study.heima.message;


public class PongMessage extends Message {
    @Override
    public int getMessageType() {
        return PongMessage;
    }
}
