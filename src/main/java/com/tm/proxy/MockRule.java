package com.tm.proxy;

import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;

@Data
public class MockRule {
    private String targetIP;
    private int targetPort;
    private String targetProtocol = "http";
    private String mockUri;
    private int mockResponseStatusCode = 200;
    private String mockResponseBody = "";
    private AtomicInteger maxWorkNum = new AtomicInteger(1);
}
