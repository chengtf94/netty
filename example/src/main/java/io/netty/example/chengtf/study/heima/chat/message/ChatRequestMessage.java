package io.netty.example.chengtf.study.heima.chat.message;

import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(callSuper = true)
public class ChatRequestMessage extends Message {
    private String content;
    private String to;
    private String from;

    public ChatRequestMessage() {
    }

    public ChatRequestMessage(String from, String to, String content) {
        this.from = from;
        this.to = to;
        this.content = content;
    }

    @Override
    public int getMessageType() {
        return ChatRequestMessage;
    }
}
