package io.netty.example.chengtf.study.heima.chatroom.message;


import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(callSuper = true)
public class GroupChatRequestMessage extends Message {
    private String content;
    private String groupName;
    private String from;

    public GroupChatRequestMessage(String from, String groupName, String content) {
        this.content = content;
        this.groupName = groupName;
        this.from = from;
    }

    @Override
    public int getMessageType() {
        return GroupChatRequestMessage;
    }
}
