package io.netty.example.studyjksj.common.order;

import io.netty.example.studyjksj.common.OperationResult;
import lombok.Data;

@Data
public class OrderOperationResult extends OperationResult {

    private final int tableId;
    private final String dish;
    private final boolean complete;

}
