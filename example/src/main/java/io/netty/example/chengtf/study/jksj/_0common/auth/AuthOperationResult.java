package io.netty.example.chengtf.study.jksj._0common.auth;

import io.netty.example.chengtf.study.jksj._0common.OperationResult;
import lombok.Data;

@Data
public class AuthOperationResult extends OperationResult {

    private final boolean passAuth;

}
