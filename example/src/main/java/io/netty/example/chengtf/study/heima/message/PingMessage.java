package io.netty.example.chengtf.study.heima.message;


public class PingMessage extends Message {
    @Override
    public int getMessageType() {
        return PingMessage;
    }
}
