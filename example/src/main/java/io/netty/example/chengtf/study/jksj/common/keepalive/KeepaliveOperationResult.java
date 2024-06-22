package io.netty.example.chengtf.study.jksj.common.keepalive;

import io.netty.example.chengtf.study.jksj.common.OperationResult;
import lombok.Data;

@Data
public class KeepaliveOperationResult extends OperationResult {

    private final long time;

}
