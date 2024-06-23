package io.netty.example.chengtf.study.jksj._0common.order;

import io.netty.example.chengtf.study.jksj._0common.OperationResult;
import lombok.Data;

/**
 * 点单操作结果
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
public class OrderOperationResult extends OperationResult {
    /**
     * 桌号
     */
    private final int tableId;
    /**
     * 点餐信息
     */
    private final String dish;
    /**
     * 是否点单完成
     */
    private final boolean complete;
}
