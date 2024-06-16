package io.netty.example.studyjksj.common;

import io.netty.example.studyjksj.common.auth.AuthOperation;
import io.netty.example.studyjksj.common.auth.AuthOperationResult;
import io.netty.example.studyjksj.common.keepalive.KeepaliveOperation;
import io.netty.example.studyjksj.common.keepalive.KeepaliveOperationResult;
import io.netty.example.studyjksj.common.order.OrderOperation;
import io.netty.example.studyjksj.common.order.OrderOperationResult;

import java.util.function.Predicate;

/**
 * 操作类型枚举
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public enum OperationType {

    AUTH(1, AuthOperation.class, AuthOperationResult.class),
    KEEPALIVE(2, KeepaliveOperation.class, KeepaliveOperationResult.class),
    ORDER(3, OrderOperation.class, OrderOperationResult.class),
    ;
    /**
     * 操作码
     */
    private int opCode;
    /**
     * 操作对应的类型
     */
    private Class<? extends Operation> operationClazz;
    /**
     * 操作结果对应的类型
     */
    private Class<? extends OperationResult> operationResultClazz;

    OperationType(int opCode, Class<? extends Operation> operationClazz, Class<? extends OperationResult> responseClass) {
        this.opCode = opCode;
        this.operationClazz = operationClazz;
        this.operationResultClazz = responseClass;
    }

    public int getOpCode() {
        return opCode;
    }

    public Class<? extends Operation> getOperationClazz() {
        return operationClazz;
    }

    public Class<? extends OperationResult> getOperationResultClazz() {
        return operationResultClazz;
    }

    public static OperationType fromOpCode(int type) {
        return getOperationType(requestType -> requestType.opCode == type);
    }

    public static OperationType fromOperation(Operation operation) {
        return getOperationType(requestType -> requestType.operationClazz == operation.getClass());
    }

    private static OperationType getOperationType(Predicate<OperationType> predicate) {
        OperationType[] values = values();
        for (OperationType operationType : values) {
            if (predicate.test(operationType)) {
                return operationType;
            }
        }
        throw new AssertionError("no found type");
    }

}
