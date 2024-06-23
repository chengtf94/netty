package io.netty.example.chengtf.study.jksj._0common.keepalive;

import io.netty.example.chengtf.study.jksj._0common.Operation;
import lombok.Data;
import lombok.extern.java.Log;

@Data
@Log
public class KeepaliveOperation extends Operation {

    private long time ;

    public KeepaliveOperation() {
        this.time = System.nanoTime();
    }

    @Override
    public KeepaliveOperationResult execute() {
        KeepaliveOperationResult orderResponse = new KeepaliveOperationResult(time);
        return orderResponse;
    }
}
