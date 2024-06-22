package io.netty.example.chengtf.study.jksj.client.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.example.chengtf.study.jksj.common.Operation;
import io.netty.example.chengtf.study.jksj.common.RequestMessage;
import io.netty.example.chengtf.study.jksj.util.IdUtil;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public class OperationToRequestMessageEncoder extends MessageToMessageEncoder<Operation> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Operation operation, List<Object> out) throws Exception {
          RequestMessage requestMessage = new RequestMessage(IdUtil.nextId(), operation);

          out.add(requestMessage);
     }
}
