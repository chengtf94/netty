package io.netty.example.chengtf.study.heima._0common.protocol;

import io.netty.example.chengtf.study.heima.chatroom.message.*;
import io.netty.example.chengtf.study.heima.rpc.message.RpcRequestMessage;
import io.netty.example.chengtf.study.heima.rpc.message.RpcResponseMessage;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 消息基类
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
public abstract class Message implements Serializable {

    /**
     * 聊天类型 byte 值
     */
    public static final int LoginRequestMessage = 0;
    public static final int LoginResponseMessage = 1;
    public static final int ChatRequestMessage = 2;
    public static final int ChatResponseMessage = 3;
    public static final int GroupCreateRequestMessage = 4;
    public static final int GroupCreateResponseMessage = 5;
    public static final int GroupJoinRequestMessage = 6;
    public static final int GroupJoinResponseMessage = 7;
    public static final int GroupQuitRequestMessage = 8;
    public static final int GroupQuitResponseMessage = 9;
    public static final int GroupChatRequestMessage = 10;
    public static final int GroupChatResponseMessage = 11;
    public static final int GroupMembersRequestMessage = 12;
    public static final int GroupMembersResponseMessage = 13;
    public static final int PingMessage = 14;
    public static final int PongMessage = 15;
    /**
     * RPC请求类型 byte 值
     */
    public static final int RPC_MESSAGE_TYPE_REQUEST = 101;
    /**
     * RPC响应类型 byte 值
     */
    public static final int RPC_MESSAGE_TYPE_RESPONSE = 102;

    /**
     * 消息类型Map：key为消息类型byte值，value为其对应的Class
     */
    private static final Map<Integer, Class<? extends Message>> messageClasses = new HashMap<>();

    static {
        messageClasses.put(LoginRequestMessage, io.netty.example.chengtf.study.heima.chatroom.message.LoginRequestMessage.class);
        messageClasses.put(LoginResponseMessage, io.netty.example.chengtf.study.heima.chatroom.message.LoginResponseMessage.class);
        messageClasses.put(ChatRequestMessage, io.netty.example.chengtf.study.heima.chatroom.message.ChatRequestMessage.class);
        messageClasses.put(ChatResponseMessage, io.netty.example.chengtf.study.heima.chatroom.message.ChatResponseMessage.class);
        messageClasses.put(GroupCreateRequestMessage, io.netty.example.chengtf.study.heima.chatroom.message.GroupCreateRequestMessage.class);
        messageClasses.put(GroupCreateResponseMessage, GroupCreateResponseMessage.class);
        messageClasses.put(GroupJoinRequestMessage, GroupJoinRequestMessage.class);
        messageClasses.put(GroupJoinResponseMessage, GroupJoinResponseMessage.class);
        messageClasses.put(GroupQuitRequestMessage, GroupQuitRequestMessage.class);
        messageClasses.put(GroupQuitResponseMessage, GroupQuitResponseMessage.class);
        messageClasses.put(GroupChatRequestMessage, GroupChatRequestMessage.class);
        messageClasses.put(GroupChatResponseMessage, GroupChatResponseMessage.class);
        messageClasses.put(GroupMembersRequestMessage, GroupMembersRequestMessage.class);
        messageClasses.put(GroupMembersResponseMessage, GroupMembersResponseMessage.class);
        messageClasses.put(RPC_MESSAGE_TYPE_REQUEST, RpcRequestMessage.class);
        messageClasses.put(RPC_MESSAGE_TYPE_RESPONSE, RpcResponseMessage.class);
    }

    /**
     * 根据消息类型字节，获得对应的消息 class
     *
     * @param messageType 消息类型字节
     * @return 消息 class
     */
    public static Class<? extends Message> getMessageClass(int messageType) {
        return messageClasses.get(messageType);
    }

    /**
     * 序号ID
     */
    private int sequenceId;

    /**
     * 消息类型
     */
    private int messageType;

    public abstract int getMessageType();

}
