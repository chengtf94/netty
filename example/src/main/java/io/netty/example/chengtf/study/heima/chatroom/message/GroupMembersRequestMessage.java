package io.netty.example.chengtf.study.heima.chatroom.message;


import io.netty.example.chengtf.study.heima.common.protocol.Message;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(callSuper = true)
public class GroupMembersRequestMessage extends Message {
    private String groupName;

    public GroupMembersRequestMessage(String groupName) {
        this.groupName = groupName;
    }

    @Override
    public int getMessageType() {
        return GroupMembersRequestMessage;
    }
}
