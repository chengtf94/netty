package io.netty.example.chengtf.study.heima.chat.server.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.example.chengtf.study.heima.chat.client.message.GroupMembersRequestMessage;
import io.netty.example.chengtf.study.heima.chat.server.message.GroupMembersResponseMessage;
import io.netty.example.chengtf.study.heima.chat.server.session.GroupSessionFactory;

import java.util.Set;

@ChannelHandler.Sharable
public class GroupMembersRequestMessageHandler extends SimpleChannelInboundHandler<GroupMembersRequestMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GroupMembersRequestMessage msg) throws Exception {
        Set<String> members = GroupSessionFactory.getGroupSession()
                .getMembers(msg.getGroupName());
        ctx.writeAndFlush(new GroupMembersResponseMessage(members));
    }
}
