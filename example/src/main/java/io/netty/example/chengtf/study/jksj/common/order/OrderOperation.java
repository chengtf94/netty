package io.netty.example.chengtf.study.jksj.common.order;

import io.netty.example.chengtf.study.jksj.common.Operation;
import lombok.Data;

/**
 * 点单操作
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
public class OrderOperation extends Operation {
    /**
     * 桌号
     */
    private int tableId;
    /**
     * 点餐信息
     */
    private String dish;

    public OrderOperation(int tableId, String dish) {
        this.tableId = tableId;
        this.dish = dish;
    }

    @Override
    public OrderOperationResult execute() {
        System.out.println("order's executing startup with orderRequest: " + toString());
        //execute order logic
        System.out.println("order's executing complete");
        return new OrderOperationResult(tableId, dish, true);
    }

}
