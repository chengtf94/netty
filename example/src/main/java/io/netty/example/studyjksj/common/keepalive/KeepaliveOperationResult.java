package io.netty.example.studyjksj.common.keepalive;

import io.netty.example.studyjksj.common.OperationResult;
import lombok.Data;

@Data
public class KeepaliveOperationResult extends OperationResult {

    private final long time;

}
