package io.netty.example.chengtf.study.jksj.common;

/**
 * 响应消息
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class ResponseMessage extends Message<OperationResult> {

    @Override
    public Class getMessageBodyDecodeClass(int opcode) {
        return OperationType.fromOpCode(opcode).getOperationResultClazz();
    }

}
