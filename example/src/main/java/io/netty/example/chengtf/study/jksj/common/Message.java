package io.netty.example.chengtf.study.jksj.common;

import io.netty.buffer.ByteBuf;
import io.netty.example.chengtf.study.jksj.util.JsonUtil;
import lombok.Data;
import lombok.Getter;

import java.nio.charset.Charset;

/**
 * 消息
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
public abstract class Message<T extends MessageBody> {

    /**
     * 消息头
     */
    private MessageHeader messageHeader;
    /**
     * 消息体
     */
    @Getter
    private T messageBody;

    /**
     * 获取消息体解码对应的反序列化类型
     */

    public abstract Class<T> getMessageBodyDecodeClass(int opcode);

    /**
     * 编码
     */
    public void encode(ByteBuf byteBuf) {
        byteBuf.writeInt(messageHeader.getVersion());
        byteBuf.writeLong(messageHeader.getStreamId());
        byteBuf.writeInt(messageHeader.getOpCode());
        byteBuf.writeBytes(JsonUtil.toJson(messageBody).getBytes());
    }

    /**
     * 解码
     */
    public void decode(ByteBuf msg) {
        int version = msg.readInt();
        long streamId = msg.readLong();
        int opCode = msg.readInt();

        MessageHeader messageHeader = new MessageHeader();
        messageHeader.setVersion(version);
        messageHeader.setOpCode(opCode);
        messageHeader.setStreamId(streamId);
        this.messageHeader = messageHeader;

        Class<T> bodyClazz = getMessageBodyDecodeClass(opCode);
        T body = JsonUtil.fromJson(msg.toString(Charset.forName("UTF-8")), bodyClazz);
        this.messageBody = body;
    }

}
