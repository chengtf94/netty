package io.netty.example.chengtf.study.heima.chatroom.message;


import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(callSuper = true)
public class GroupJoinRequestMessage extends Message {
    private String groupName;

    private String username;

    public GroupJoinRequestMessage(String username, String groupName) {
        this.groupName = groupName;
        this.username = username;
    }

    @Override
    public int getMessageType() {
        return GroupJoinRequestMessage;
    }
}
