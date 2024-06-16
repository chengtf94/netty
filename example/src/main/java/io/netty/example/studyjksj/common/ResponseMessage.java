package io.netty.example.studyjksj.common;

public class ResponseMessage extends Message <OperationResult>{
    @Override
    public Class getMessageBodyDecodeClass(int opcode) {
        return OperationType.fromOpCode(opcode).getOperationResultClazz();
    }
}
