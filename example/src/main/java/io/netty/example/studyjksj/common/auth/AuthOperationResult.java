package io.netty.example.studyjksj.common.auth;

import io.netty.example.studyjksj.common.OperationResult;
import lombok.Data;

@Data
public class AuthOperationResult extends OperationResult {

    private final boolean passAuth;

}
